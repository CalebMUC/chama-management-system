package ke.chama.modules.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.modules.auth.application.AuthProperties;
import ke.chama.modules.auth.application.port.out.OneTimeTokenRepository;
import ke.chama.modules.auth.domain.event.AccountSetupRequested;
import ke.chama.modules.auth.domain.event.EmailVerificationRequested;
import ke.chama.modules.auth.domain.event.PasswordResetRequested;
import ke.chama.modules.auth.domain.exception.InvalidLinkException;
import ke.chama.modules.auth.domain.model.OneTimeToken;
import ke.chama.modules.auth.domain.model.TokenPurpose;
import ke.chama.modules.auth.domain.model.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues and redeems the single-use links sent by email.
 * <p>
 * Issuing publishes an event; the notification module sends the email after the transaction
 * commits. If sending fails, the event stays in the outbox and is retried, and the link stays
 * valid until it expires.
 */
@Component
class AccountLinks {

    static final String VERIFY_EMAIL_PATH = "/verify-email";
    static final String RESET_PASSWORD_PATH = "/reset-password";

    private final OneTimeTokenRepository tokens;
    private final ApplicationEventPublisher events;
    private final AuthProperties.Links config;

    AccountLinks(OneTimeTokenRepository tokens, ApplicationEventPublisher events, AuthProperties properties) {
        this.tokens = tokens;
        this.events = events;
        this.config = properties.links();
    }

    void sendEmailVerification(User user, Instant now) {
        Duration ttl = config.emailVerificationTtl();
        String url = issue(user, TokenPurpose.EMAIL_VERIFICATION, VERIFY_EMAIL_PATH, ttl, now);
        events.publishEvent(new EmailVerificationRequested(UUID.randomUUID(), now, user.getId(),
                user.getEmail(), user.getFirstName(), url, now.plus(ttl)));
    }

    void sendPasswordReset(User user, Instant now) {
        Duration ttl = config.passwordResetTtl();
        String url = issue(user, TokenPurpose.PASSWORD_RESET, RESET_PASSWORD_PATH, ttl, now);
        events.publishEvent(new PasswordResetRequested(UUID.randomUUID(), now, user.getId(),
                user.getEmail(), user.getFirstName(), url, now.plus(ttl)));
    }

    /**
     * For an account someone else created (e.g. an imported member): a link to choose a password.
     * It is a password-reset link with a longer life; using it also confirms the email address.
     */
    void sendAccountSetup(User user, String chamaName, Instant now) {
        Duration ttl = config.accountSetupTtl();
        String url = issue(user, TokenPurpose.PASSWORD_RESET, RESET_PASSWORD_PATH, ttl, now);
        events.publishEvent(new AccountSetupRequested(UUID.randomUUID(), now, user.getId(), user.getEmail(),
                user.getFirstName(), chamaName, TenantContext.code().orElse(null), url, now.plus(ttl)));
    }

    /** True if a link of this kind went out within the resend interval: don't flood the inbox. */
    boolean sentRecently(User user, TokenPurpose purpose, Instant now) {
        return tokens.latestCreatedAt(user.getId(), purpose)
                .map(last -> last.isAfter(now.minus(config.resendInterval())))
                .orElse(false);
    }

    /** Redeems a link. Returns its user, or throws InvalidLinkException. Each link works once. */
    User redeem(String rawToken, TokenPurpose purpose, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidLinkException();
        }
        OneTimeToken token = tokens.findByTokenHash(OpaqueTokens.sha256Hex(rawToken.trim()))
                .filter(t -> t.isUsableFor(purpose, now))
                .orElseThrow(InvalidLinkException::new);
        if (tokens.markUsedIfUsable(token.getId(), now) == 0) {
            throw new InvalidLinkException();
        }
        return token.getUser();
    }

    void invalidateOutstanding(User user, TokenPurpose purpose, Instant now) {
        tokens.invalidateActive(user.getId(), purpose, now);
    }

    private String issue(User user, TokenPurpose purpose, String path, Duration ttl, Instant now) {
        tokens.invalidateActive(user.getId(), purpose, now);
        String raw = OpaqueTokens.generate();
        tokens.save(new OneTimeToken(user, purpose, OpaqueTokens.sha256Hex(raw), now, now.plus(ttl)));
        // The chama code tells the frontend which X-Chama-Code to send when posting the token back.
        String chama = TenantContext.code()
                .map(code -> "chama=" + URLEncoder.encode(code, StandardCharsets.UTF_8) + "&")
                .orElse("");
        return config.frontendBaseUrl() + path + "?" + chama + "token=" + raw;
    }
}
