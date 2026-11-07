package ke.chama.modules.auth.application.service;

import ke.chama.modules.auth.application.port.in.AuthTokens;
import ke.chama.shared.tenancy.TenantContext;
import java.util.UUID;
import ke.chama.modules.auth.application.AuthProperties;
import ke.chama.modules.auth.application.port.in.LoginCommand;
import ke.chama.modules.auth.application.port.out.AccessTokenIssuer;
import ke.chama.modules.auth.application.port.out.LoginAttemptRepository;
import ke.chama.modules.auth.application.port.out.OneTimeTokenRepository;
import ke.chama.modules.auth.application.port.out.PasswordHasher;
import ke.chama.modules.auth.application.port.out.RefreshTokenRepository;
import ke.chama.modules.auth.application.port.out.RoleRepository;
import ke.chama.modules.auth.application.port.out.UserRepository;
import ke.chama.modules.auth.domain.exception.AccountLockedException;
import ke.chama.modules.auth.domain.exception.InvalidCredentialsException;
import ke.chama.modules.auth.domain.exception.InvalidTokenException;
import ke.chama.modules.auth.domain.model.LoginAttempt;
import ke.chama.modules.auth.domain.model.LoginOutcome;
import ke.chama.modules.auth.domain.model.RefreshToken;
import ke.chama.modules.auth.domain.model.RevocationReason;
import ke.chama.modules.auth.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Branches that are hard to provoke over HTTP: timing equalization, racing refreshes, lock timing. */
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final LoginAttemptRepository attempts = mock(LoginAttemptRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final AccessTokenIssuer issuer = mock(AccessTokenIssuer.class);

    private static final UUID CHAMA = UUID.randomUUID();

    private AuthService service;

    /** Login looks accounts up in the chama the request names, so it needs a tenant. */
    private AuthTokens loginToChama(LoginCommand command) {
        return TenantContext.callAs(CHAMA, "test-chama", () -> service.login(command));
    }

    @BeforeEach
    void setUp() {
        when(hasher.hash(anyString())).thenReturn("{bcrypt}dummy");
        when(attempts.lastSuccessAt(anyString())).thenReturn(Optional.empty());
        when(attempts.recentBadCredentialTimes(anyString(), any(), anyInt())).thenReturn(Collections.emptyList());

        AuthProperties properties = new AuthProperties("test", "x".repeat(32), Duration.ofMinutes(15), Duration.ofDays(7),
                false,
                new AuthProperties.Lockout(5, LOCKOUT),
                new AuthProperties.Links("http://app", Duration.ofHours(24), Duration.ofMinutes(30), Duration.ofSeconds(60),
                        Duration.ofDays(7)));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

        service = new AuthService(users, mock(RoleRepository.class), refreshTokens, hasher,
                new SessionIssuer(issuer, refreshTokens, properties),
                new LoginThrottle(attempts, properties),
                new AccountLinks(mock(OneTimeTokenRepository.class), events, properties),
                properties, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unknownEmailStillPaysForAPasswordCheckAndIsRecorded() {
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginToChama(new LoginCommand("ghost@example.com", "whatever1", "10.0.0.7", "curl")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(hasher).matches("whatever1", "{bcrypt}dummy");
        ArgumentCaptor<LoginAttempt> recorded = ArgumentCaptor.forClass(LoginAttempt.class);
        verify(attempts).save(recorded.capture());
        assertThat(recorded.getValue().getOutcome()).isEqualTo(LoginOutcome.BAD_CREDENTIALS);
        assertThat(recorded.getValue().getIpAddress()).isEqualTo("10.0.0.7");
        assertThat(recorded.getValue().getUserId()).isNull();
    }

    @Test
    void lockLiftsWhenTheOldestCountedFailureLeavesTheWindow() {
        List<Instant> fiveFailures = List.of(
                NOW.minusSeconds(60), NOW.minusSeconds(120), NOW.minusSeconds(180),
                NOW.minusSeconds(240), NOW.minusSeconds(300));
        when(attempts.recentBadCredentialTimes(eq("jane@example.com"), any(), eq(5))).thenReturn(fiveFailures);

        assertThatThrownBy(() -> loginToChama(new LoginCommand("jane@example.com", "Correct2026")))
                .isInstanceOfSatisfying(AccountLockedException.class, e ->
                        assertThat(e.retryAfter()).isEqualTo(LOCKOUT.minusSeconds(300)));

        verify(hasher, never()).matches(anyString(), anyString());
    }

    @Test
    void overlongPasswordIsRejectedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> loginToChama(new LoginCommand("a@b.co", "p".repeat(73))))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(users, never()).findByEmail(anyString());
    }

    @Test
    void losingARefreshRaceRevokesAllSessions() {
        User user = new User(CHAMA, "a@b.co", "0712345678", "{bcrypt}x", "A", "B");
        RefreshToken token = new RefreshToken(user, "hash", NOW.plus(Duration.ofDays(1)));
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(refreshTokens.revokeIfActive(any(), eq(NOW), eq(RevocationReason.ROTATED))).thenReturn(0);

        assertThatThrownBy(() -> service.refresh("raw-token")).isInstanceOf(InvalidTokenException.class);

        verify(refreshTokens).revokeAllActiveForUser(user.getId(), NOW, RevocationReason.REUSE_DETECTED);
        verify(issuer, never()).issue(any(), any());
    }
}
