package ke.chama.modules.notification.application.service;

import ke.chama.modules.notification.application.port.out.EmailMessage;
import ke.chama.modules.notification.application.port.out.EmailSender;
import ke.chama.modules.submission.domain.event.PaymentSubmissionReviewed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Tells a member what happened to a payment they reported, after the transaction commits. An
 * approved contribution is left out: the contribution receipt already confirms it.
 */
@Component
class PaymentSubmissionEmailNotifier {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final EmailSender emailSender;

    PaymentSubmissionEmailNotifier(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @ApplicationModuleListener
    void on(PaymentSubmissionReviewed event) {
        if (event.approved() && "CONTRIBUTION".equals(event.kind())) {
            return;
        }
        String amount = ContributionEmailNotifier.format(event.amountMinor(), event.currency());
        String what = switch (event.kind()) {
            case "PENALTY_PAYMENT" -> "penalty payment";
            case "LOAN_REPAYMENT" -> "loan repayment";
            default -> "contribution";
        };
        String reference = event.reference() == null ? "" : " (ref. " + event.reference() + ")";
        String name = event.memberFirstName() == null ? "member" : event.memberFirstName();
        if (event.approved()) {
            emailSender.send(new EmailMessage(event.memberEmail(), "Payment confirmed: " + amount, """
                    Hello %s,

                    %s confirmed the %s of %s%s you made on %s. It is now on your record.
                    """.formatted(name, event.chamaName(), what, amount, reference, DATE.format(event.paidOn()))));
            return;
        }
        emailSender.send(new EmailMessage(event.memberEmail(), "Payment not confirmed: " + amount, """
                Hello %s,

                %s could not confirm the %s of %s%s you reported for %s.

                Reason: %s

                If you did pay, report it again with the correct details, or talk to your chama's treasurer.
                """.formatted(name, event.chamaName(), what, amount, reference, DATE.format(event.paidOn()),
                event.reason() == null ? "not given" : event.reason())));
    }
}
