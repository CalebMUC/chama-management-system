package ke.chama.modules.auth.application.service;

import ke.chama.modules.auth.application.AuthProperties;
import ke.chama.modules.auth.application.port.in.LoginCommand;
import ke.chama.modules.auth.application.port.out.AccessTokenIssuer;
import ke.chama.modules.auth.application.port.out.PasswordHasher;
import ke.chama.modules.auth.application.port.out.RefreshTokenRepository;
import ke.chama.modules.auth.application.port.out.RoleRepository;
import ke.chama.modules.auth.application.port.out.UserRepository;
import ke.chama.modules.auth.domain.exception.InvalidCredentialsException;
import ke.chama.modules.auth.domain.exception.InvalidTokenException;
import ke.chama.modules.auth.domain.model.RefreshToken;
import ke.chama.modules.auth.domain.model.RevocationReason;
import ke.chama.modules.auth.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Branches that are hard to provoke over HTTP: timing equalization and racing refreshes. */
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");

    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final AccessTokenIssuer issuer = mock(AccessTokenIssuer.class);

    private AuthService service;

    @BeforeEach
    void setUp() {
        when(hasher.hash(anyString())).thenReturn("{bcrypt}dummy");
        AuthProperties properties = new AuthProperties("test", "x".repeat(32), Duration.ofMinutes(15), Duration.ofDays(7));
        service = new AuthService(users, mock(RoleRepository.class), refreshTokens, hasher, issuer, properties,
                mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unknownEmailStillPaysForAPasswordCheck() {
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginCommand("ghost@example.com", "whatever1")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(hasher).matches("whatever1", "{bcrypt}dummy");
    }

    @Test
    void overlongPasswordIsRejectedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.login(new LoginCommand("a@b.co", "p".repeat(73))))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(users, never()).findByEmail(anyString());
    }

    @Test
    void losingARefreshRaceRevokesAllSessions() {
        User user = new User("a@b.co", "0712345678", "{bcrypt}x", "A", "B");
        RefreshToken token = new RefreshToken(user, "hash", NOW.plus(Duration.ofDays(1)));
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(refreshTokens.revokeIfActive(any(), eq(NOW), eq(RevocationReason.ROTATED))).thenReturn(0);

        assertThatThrownBy(() -> service.refresh("raw-token")).isInstanceOf(InvalidTokenException.class);

        verify(refreshTokens).revokeAllActiveForUser(user.getId(), NOW, RevocationReason.REUSE_DETECTED);
        verify(issuer, never()).issue(any(), any());
    }
}
