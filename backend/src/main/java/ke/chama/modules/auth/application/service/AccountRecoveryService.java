package ke.chama.modules.auth.application.service;

import ke.chama.modules.auth.application.port.in.AuthTokens;
import ke.chama.modules.auth.application.port.in.ChangePasswordUseCase;
import ke.chama.modules.auth.application.port.in.EmailVerificationUseCase;
import ke.chama.modules.auth.application.port.in.PasswordResetUseCase;
import ke.chama.modules.auth.application.port.out.PasswordHasher;
import ke.chama.modules.auth.application.port.out.UserRepository;
import ke.chama.modules.auth.domain.event.PasswordChanged;
import ke.chama.modules.auth.domain.exception.IncorrectCurrentPasswordException;
import ke.chama.modules.auth.domain.exception.UserNotFoundException;
import ke.chama.modules.auth.domain.model.RevocationReason;
import ke.chama.modules.auth.domain.model.TokenPurpose;
import ke.chama.modules.auth.domain.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Email verification, forgot/reset password, and change password. */
@Service
@Transactional
public class AccountRecoveryService implements EmailVerificationUseCase, PasswordResetUseCase, ChangePasswordUseCase {

    private static final Logger log = LoggerFactory.getLogger(AccountRecoveryService.class);

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final AccountLinks accountLinks;
    private final SessionIssuer sessions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AccountRecoveryService(UserRepository users,
                                  PasswordHasher passwordHasher,
                                  AccountLinks accountLinks,
                                  SessionIssuer sessions,
                                  ApplicationEventPublisher events,
                                  Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.accountLinks = accountLinks;
        this.sessions = sessions;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public void requestVerification(String email) {
        Instant now = clock.instant();
        findEnabled(email)
                .filter(user -> !user.isEmailVerified())
                .filter(user -> !accountLinks.sentRecently(user, TokenPurpose.EMAIL_VERIFICATION, now))
                .ifPresent(user -> accountLinks.sendEmailVerification(user, now));
    }

    @Override
    public void verify(String token) {
        Instant now = clock.instant();
        User user = accountLinks.redeem(token, TokenPurpose.EMAIL_VERIFICATION, now);
        user.markEmailVerified(now);
        log.info("User {} verified their email", user.getId());
    }

    @Override
    public void requestReset(String email) {
        Instant now = clock.instant();
        findEnabled(email)
                .filter(user -> !accountLinks.sentRecently(user, TokenPurpose.PASSWORD_RESET, now))
                .ifPresent(user -> {
                    accountLinks.sendPasswordReset(user, now);
                    log.info("Password reset requested for user {}", user.getId());
                });
    }

    @Override
    public void reset(String token, String newPassword) {
        // Check the new password first, so a rejected password does not use up the link.
        PasswordPolicy.requireAcceptable(newPassword);
        Instant now = clock.instant();

        User user = accountLinks.redeem(token, TokenPurpose.PASSWORD_RESET, now);
        user.changePassword(passwordHasher.hash(newPassword), now);
        user.markEmailVerified(now);
        accountLinks.invalidateOutstanding(user, TokenPurpose.PASSWORD_RESET, now);
        int ended = sessions.endAll(user.getId(), RevocationReason.PASSWORD_CHANGED, now);

        events.publishEvent(PasswordChanged.of(user.getId(), user.getEmail(), user.getFirstName(),
                PasswordChanged.Method.RESET_LINK, now));
        log.info("User {} reset their password; ended {} session(s)", user.getId(), ended);
    }

    @Override
    public AuthTokens changePassword(UUID userId, String currentPassword, String newPassword) {
        Instant now = clock.instant();
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));

        if (currentPassword == null || !passwordHasher.matches(currentPassword, user.getPassword())) {
            throw new IncorrectCurrentPasswordException();
        }
        PasswordPolicy.requireAcceptable(newPassword);

        user.changePassword(passwordHasher.hash(newPassword), now);
        int ended = sessions.endAll(user.getId(), RevocationReason.PASSWORD_CHANGED, now);
        events.publishEvent(PasswordChanged.of(user.getId(), user.getEmail(), user.getFirstName(),
                PasswordChanged.Method.CHANGED_WHILE_LOGGED_IN, now));

        log.info("User {} changed their password; ended {} other session(s)", user.getId(), ended);
        return sessions.start(user, now);
    }

    private Optional<User> findEnabled(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return users.findByEmail(User.normalizeEmail(email)).filter(User::isEnabled);
    }
}
