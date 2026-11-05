package ke.chama.modules.auth.application.service;

import ke.chama.modules.auth.application.AuthProperties;
import ke.chama.modules.auth.application.port.in.AuthTokens;
import ke.chama.modules.auth.application.port.in.GetUserProfileUseCase;
import ke.chama.modules.auth.application.port.in.LoginCommand;
import ke.chama.modules.auth.application.port.in.LoginUseCase;
import ke.chama.modules.auth.application.port.in.LogoutUseCase;
import ke.chama.modules.auth.application.port.in.RefreshSessionUseCase;
import ke.chama.modules.auth.application.port.in.RegisterUserCommand;
import ke.chama.modules.auth.application.port.in.RegisterUserUseCase;
import ke.chama.modules.auth.application.port.in.UserProfile;
import ke.chama.modules.auth.application.port.out.AccessTokenIssuer;
import ke.chama.modules.auth.application.port.out.PasswordHasher;
import ke.chama.modules.auth.application.port.out.RefreshTokenRepository;
import ke.chama.modules.auth.application.port.out.RoleRepository;
import ke.chama.modules.auth.application.port.out.UserRepository;
import ke.chama.modules.auth.domain.exception.AccountDisabledException;
import ke.chama.modules.auth.domain.exception.InvalidCredentialsException;
import ke.chama.modules.auth.domain.exception.InvalidTokenException;
import ke.chama.modules.auth.domain.exception.UserAlreadyExistsException;
import ke.chama.modules.auth.domain.exception.UserNotFoundException;
import ke.chama.modules.auth.domain.model.RefreshToken;
import ke.chama.modules.auth.domain.model.RevocationReason;
import ke.chama.modules.auth.domain.model.Role;
import ke.chama.modules.auth.domain.model.User;
import ke.chama.shared.domain.exception.DomainValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AuthService implements RegisterUserUseCase, LoginUseCase, RefreshSessionUseCase,
        LogoutUseCase, GetUserProfileUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** BCrypt only uses the first 72 bytes; longer passwords would be silently truncated. */
    static final int MAX_PASSWORD_BYTES = 72;
    static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer accessTokenIssuer;
    private final AuthProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * Compared against when the email is unknown, so a failed login takes as long for a
     * non-existent account as for a wrong password.
     */
    private final String timingEqualizerHash;

    public AuthService(UserRepository users,
                       RoleRepository roles,
                       RefreshTokenRepository refreshTokens,
                       PasswordHasher passwordHasher,
                       AccessTokenIssuer accessTokenIssuer,
                       AuthProperties properties,
                       ApplicationEventPublisher events,
                       Clock clock) {
        this.users = users;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.passwordHasher = passwordHasher;
        this.accessTokenIssuer = accessTokenIssuer;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
        this.timingEqualizerHash = passwordHasher.hash(OpaqueTokens.generate());
    }

    @Override
    public UserProfile register(RegisterUserCommand command) {
        String email = User.normalizeEmail(command.email());
        if (users.existsByEmail(email)) {
            throw new UserAlreadyExistsException(email);
        }
        requireAcceptablePassword(command.password());

        Role defaultRole = roles.findByName(Role.USER)
                .orElseThrow(() -> new IllegalStateException(Role.USER + " is missing; check the auth migrations"));

        User user = User.register(email, command.phoneNumber(), passwordHasher.hash(command.password()),
                command.firstName(), command.lastName(), defaultRole, clock.instant());
        users.save(user);
        user.pullDomainEvents().forEach(events::publishEvent);

        log.info("Registered user {}", user.getId());
        return UserProfile.from(user);
    }

    @Override
    public AuthTokens login(LoginCommand command) {
        String password = command.password() == null ? "" : command.password();
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidCredentialsException();
        }

        User user = users.findByEmail(User.normalizeEmail(command.email())).orElse(null);
        boolean passwordMatches = passwordHasher.matches(password,
                user != null ? user.getPassword() : timingEqualizerHash);

        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled()) {
            throw new AccountDisabledException();
        }
        return startSession(user, clock.instant());
    }

    /**
     * The rotation and reuse checks must commit even though they end in an exception;
     * otherwise rolling back would undo the "revoke every session" response to a stolen token.
     */
    @Override
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthTokens refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = findByRawToken(rawRefreshToken)
                .orElseThrow(AuthService::invalidRefreshToken);
        UUID userId = token.getUser().getId();

        if (token.isRevoked()) {
            // Only a token that was already exchanged coming back means a copy exists elsewhere.
            // A token ended by logout is simply dead: a client retrying with it is not an attack,
            // and must not log the user out of their other devices.
            if (token.getRevokedReason() == RevocationReason.ROTATED) {
                revokeAllAfterReuse(userId, token.getId(), now);
            }
            throw invalidRefreshToken();
        }
        if (token.isExpired(now)) {
            throw invalidRefreshToken();
        }
        if (refreshTokens.revokeIfActive(token.getId(), now, RevocationReason.ROTATED) == 0) {
            // Another request used this token between our read and our update.
            revokeAllAfterReuse(userId, token.getId(), now);
            throw invalidRefreshToken();
        }

        User user = token.getUser();
        if (!user.isEnabled()) {
            throw new AccountDisabledException();
        }
        return startSession(user, now);
    }

    @Override
    public void logout(String rawRefreshToken) {
        findByRawToken(rawRefreshToken).ifPresent(token ->
                refreshTokens.revokeIfActive(token.getId(), clock.instant(), RevocationReason.LOGOUT));
    }

    @Override
    public void logoutAllSessions(UUID userId) {
        int revoked = refreshTokens.revokeAllActiveForUser(userId, clock.instant(), RevocationReason.LOGOUT_ALL);
        log.info("User {} logged out of {} session(s)", userId, revoked);
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID userId) {
        return users.findById(userId)
                .map(UserProfile::from)
                .orElseThrow(() -> new UserNotFoundException(userId.toString()));
    }

    private AuthTokens startSession(User user, Instant now) {
        AccessTokenIssuer.IssuedAccessToken accessToken = accessTokenIssuer.issue(user, now);

        String rawRefreshToken = OpaqueTokens.generate();
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        refreshTokens.save(new RefreshToken(user, OpaqueTokens.sha256Hex(rawRefreshToken), refreshExpiresAt));

        return new AuthTokens(accessToken.value(), accessToken.expiresAt(), rawRefreshToken, refreshExpiresAt);
    }

    private Optional<RefreshToken> findByRawToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Optional.empty();
        }
        return refreshTokens.findByTokenHash(OpaqueTokens.sha256Hex(rawRefreshToken.trim()));
    }

    private void revokeAllAfterReuse(UUID userId, UUID tokenId, Instant now) {
        int revoked = refreshTokens.revokeAllActiveForUser(userId, now, RevocationReason.REUSE_DETECTED);
        log.warn("Refresh token {} was reused; revoked {} active session(s) of user {}", tokenId, revoked, userId);
    }

    private static void requireAcceptablePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new DomainValidationException("AUTH_WEAK_PASSWORD",
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new DomainValidationException("AUTH_PASSWORD_TOO_LONG",
                    "Password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
    }

    private static InvalidTokenException invalidRefreshToken() {
        return new InvalidTokenException("Refresh token is invalid or expired. Please log in again.");
    }
}
