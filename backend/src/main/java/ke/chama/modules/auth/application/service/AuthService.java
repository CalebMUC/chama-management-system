package ke.chama.modules.auth.application.service;

import ke.chama.modules.auth.api.UserProvisioning;
import ke.chama.modules.auth.api.UserSummary;
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
import ke.chama.modules.auth.application.port.out.PasswordHasher;
import ke.chama.modules.auth.application.port.out.RefreshTokenRepository;
import ke.chama.modules.auth.application.port.out.RoleRepository;
import ke.chama.modules.auth.application.port.out.UserRepository;
import ke.chama.modules.auth.domain.exception.AccountDisabledException;
import ke.chama.modules.auth.domain.exception.AccountLockedException;
import ke.chama.modules.auth.domain.exception.AuthException;
import ke.chama.modules.auth.domain.exception.EmailNotVerifiedException;
import ke.chama.modules.auth.domain.exception.InvalidCredentialsException;
import ke.chama.modules.auth.domain.exception.InvalidTokenException;
import ke.chama.modules.auth.domain.exception.UserAlreadyExistsException;
import ke.chama.modules.auth.domain.exception.UserNotFoundException;
import ke.chama.modules.auth.domain.model.LoginOutcome;
import ke.chama.modules.auth.domain.model.RefreshToken;
import ke.chama.modules.auth.domain.model.RevocationReason;
import ke.chama.modules.auth.domain.model.Role;
import ke.chama.modules.auth.domain.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import ke.chama.shared.tenancy.TenantContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AuthService implements RegisterUserUseCase, LoginUseCase, RefreshSessionUseCase,
        LogoutUseCase, GetUserProfileUseCase, UserProvisioning {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher passwordHasher;
    private final SessionIssuer sessions;
    private final LoginThrottle throttle;
    private final AccountLinks accountLinks;
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
                       SessionIssuer sessions,
                       LoginThrottle throttle,
                       AccountLinks accountLinks,
                       AuthProperties properties,
                       ApplicationEventPublisher events,
                       Clock clock) {
        this.users = users;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.passwordHasher = passwordHasher;
        this.sessions = sessions;
        this.throttle = throttle;
        this.accountLinks = accountLinks;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
        this.timingEqualizerHash = passwordHasher.hash(OpaqueTokens.generate());
    }

    /**
     * Self-registration into the chama named by the request ({@code X-Chama-Code}). The new
     * account applies for membership; a chama official approves it. Nobody can self-register
     * into the platform tenant.
     */
    @Override
    public UserProfile register(RegisterUserCommand command) {
        User user = create(TenantContext.requireChama(), command, true);
        accountLinks.sendEmailVerification(user, clock.instant());
        log.info("Registered user {} in chama {}", user.getId(), user.getTenantId());
        return UserProfile.from(user);
    }

    /** For other modules, e.g. a new chama's first administrator. Runs in the caller's transaction. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UserSummary createAccount(NewAccount account) {
        User user = create(TenantContext.requireId(), new RegisterUserCommand(account.email(), account.phoneNumber(),
                account.password(), account.firstName(), account.lastName()), false);
        accountLinks.sendEmailVerification(user, clock.instant());
        return summary(user);
    }

    /** For other modules: an account whose owner chooses a password from a welcome email. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UserSummary importAccount(ImportedAccount account, String chamaName) {
        // Nobody knows this password; the owner replaces it through the link in the welcome email.
        String unknowable = OpaqueTokens.generate() + "Aa1";
        User user = create(TenantContext.requireId(), new RegisterUserCommand(account.email(), account.phoneNumber(),
                unknowable, account.firstName(), account.lastName()), false);
        accountLinks.sendAccountSetup(user, chamaName, clock.instant());
        return summary(user);
    }

    private static UserSummary summary(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.isEmailVerified(), user.getPhoneNumber(),
                user.getFirstName(), user.getLastName(), user.isEnabled());
    }

    private User create(UUID tenantId, RegisterUserCommand command, boolean selfRegistered) {
        Instant now = clock.instant();
        String email = User.normalizeEmail(command.email());
        if (users.existsByEmail(email)) {
            throw new UserAlreadyExistsException(email);
        }
        PasswordPolicy.requireAcceptable(command.password());

        Role defaultRole = roles.findByName(Role.USER)
                .orElseThrow(() -> new IllegalStateException(Role.USER + " is missing; check the auth migrations"));

        User user = User.register(tenantId, email, command.phoneNumber(), passwordHasher.hash(command.password()),
                command.firstName(), command.lastName(), defaultRole, selfRegistered, now);
        users.save(user);
        user.pullDomainEvents().forEach(events::publishEvent);
        return user;
    }

    /**
     * Failed attempts must be recorded even though the method then throws, so the transaction
     * must not roll back on auth failures; otherwise lockout could never trigger.
     */
    @Override
    @Transactional(noRollbackFor = AuthException.class)
    public AuthTokens login(LoginCommand command) {
        // Accounts are looked up in the chama the request names; fail clearly on an unknown code.
        TenantContext.requireId();
        Instant now = clock.instant();
        String password = command.password() == null ? "" : command.password();
        if (PasswordPolicy.exceedsMaxBytes(password)) {
            throw new InvalidCredentialsException();
        }

        String email = User.normalizeEmail(command.email());
        User user = users.findByEmail(email).orElse(null);

        Optional<Instant> lockedUntil = throttle.lockedUntil(email, user, now);
        if (lockedUntil.isPresent()) {
            throttle.record(email, user, LoginOutcome.ACCOUNT_LOCKED, command.ipAddress(), command.userAgent(), now);
            throw new AccountLockedException(Duration.between(now, lockedUntil.get()));
        }

        boolean passwordMatches = passwordHasher.matches(password,
                user != null ? user.getPassword() : timingEqualizerHash);
        if (user == null || !passwordMatches) {
            throttle.record(email, user, LoginOutcome.BAD_CREDENTIALS, command.ipAddress(), command.userAgent(), now);
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled()) {
            throttle.record(email, user, LoginOutcome.ACCOUNT_DISABLED, command.ipAddress(), command.userAgent(), now);
            throw new AccountDisabledException();
        }
        if (properties.requireVerifiedEmail() && !user.isEmailVerified()) {
            throttle.record(email, user, LoginOutcome.EMAIL_NOT_VERIFIED, command.ipAddress(), command.userAgent(), now);
            throw new EmailNotVerifiedException();
        }

        throttle.record(email, user, LoginOutcome.SUCCESS, command.ipAddress(), command.userAgent(), now);
        return sessions.start(user, now);
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
        return sessions.start(user, now);
    }

    @Override
    public void logout(String rawRefreshToken) {
        findByRawToken(rawRefreshToken).ifPresent(token ->
                refreshTokens.revokeIfActive(token.getId(), clock.instant(), RevocationReason.LOGOUT));
    }

    @Override
    public void logoutAllSessions(UUID userId) {
        int revoked = sessions.endAll(userId, RevocationReason.LOGOUT_ALL, clock.instant());
        log.info("User {} logged out of {} session(s)", userId, revoked);
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID userId) {
        return users.findById(userId)
                .map(UserProfile::from)
                .orElseThrow(() -> new UserNotFoundException(userId.toString()));
    }

    private Optional<RefreshToken> findByRawToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Optional.empty();
        }
        return refreshTokens.findByTokenHash(OpaqueTokens.sha256Hex(rawRefreshToken.trim()));
    }

    private void revokeAllAfterReuse(UUID userId, UUID tokenId, Instant now) {
        int revoked = sessions.endAll(userId, RevocationReason.REUSE_DETECTED, now);
        log.warn("Refresh token {} was reused; revoked {} active session(s) of user {}", tokenId, revoked, userId);
    }

    private static InvalidTokenException invalidRefreshToken() {
        return new InvalidTokenException("Refresh token is invalid or expired. Please log in again.");
    }
}
