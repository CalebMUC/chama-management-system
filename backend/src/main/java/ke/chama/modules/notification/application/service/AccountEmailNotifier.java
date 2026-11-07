package ke.chama.modules.notification.application.service;

import ke.chama.modules.auth.domain.event.AccountSetupRequested;
import ke.chama.modules.auth.domain.event.EmailVerificationRequested;
import ke.chama.modules.auth.domain.event.PasswordChanged;
import ke.chama.modules.auth.domain.event.PasswordResetRequested;
import ke.chama.modules.notification.application.port.out.EmailMessage;
import ke.chama.modules.notification.application.port.out.EmailSender;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Account emails, sent after the auth transaction commits ({@code @ApplicationModuleListener}
 * = async + after-commit + own transaction).
 * <p>
 * If sending throws, Spring Modulith keeps the event as incomplete and republishes it on
 * restart. Nothing is lost when the mail server is down. Templates must stay idempotent-safe:
 * a retried event sends the same email again, which is harmless.
 */
@Component
class AccountEmailNotifier {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm z")
            .withZone(ZoneId.of("Africa/Nairobi"));

    private final EmailSender emailSender;

    AccountEmailNotifier(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @ApplicationModuleListener
    void on(EmailVerificationRequested event) {
        emailSender.send(new EmailMessage(event.email(), "Verify your email address", """
                Hello %s,

                Please confirm this email address for your Chama account by opening the link below:

                %s

                The link expires on %s and can only be used once.

                If you did not create a Chama account, you can ignore this email.
                """.formatted(event.firstName(), event.verificationUrl(), WHEN.format(event.expiresAt()))));
    }

    @ApplicationModuleListener
    void on(AccountSetupRequested event) {
        emailSender.send(new EmailMessage(event.email(), event.chamaName() + " is now on Chama: set your password", """
                Hello %s,

                %s now keeps its records in Chama. An account was created for you with this email
                address, with your savings, loans and penalties brought forward from the chama's records.

                Choose your password here:

                %s

                The link expires on %s and can only be used once. If it expires, use "Forgot password"
                on the login page%s.

                If you think this is a mistake, contact your chama's officials.
                """.formatted(event.firstName(), event.chamaName(), event.setupUrl(), WHEN.format(event.expiresAt()),
                event.chamaCode() == null ? "" : " (chama code: " + event.chamaCode() + ")")));
    }

    @ApplicationModuleListener
    void on(PasswordResetRequested event) {
        emailSender.send(new EmailMessage(event.email(), "Reset your password", """
                Hello %s,

                We received a request to reset the password for your Chama account.
                Open the link below to choose a new password:

                %s

                The link expires on %s and can only be used once.
                Resetting your password logs you out of all devices.

                If you did not ask for this, ignore this email. Your password has not changed.
                """.formatted(event.firstName(), event.resetUrl(), WHEN.format(event.expiresAt()))));
    }

    @ApplicationModuleListener
    void on(PasswordChanged event) {
        emailSender.send(new EmailMessage(event.email(), "Your password was changed", """
                Hello %s,

                The password for your Chama account was changed on %s.

                If this was you, no action is needed.
                If it was not you, reset your password immediately using "Forgot password"
                and contact your chama's officials.
                """.formatted(event.firstName(), WHEN.format(event.occurredAt()))));
    }
}
