package ke.chama.modules.notification.application.service;

import ke.chama.modules.notification.application.port.out.EmailMessage;
import ke.chama.modules.notification.application.port.out.EmailSender;
import ke.chama.modules.penalty.domain.event.PenaltyAssessed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Tells a member they were penalised, after the transaction commits. */
@Component
class PenaltyEmailNotifier {

    private final EmailSender emailSender;

    PenaltyEmailNotifier(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @ApplicationModuleListener
    void on(PenaltyAssessed event) {
        String amount = ContributionEmailNotifier.format(event.amountMinor(), event.currency());
        String why = "FINE".equals(event.kind()) ? "You have been fined" : "A late-contribution penalty was added";
        emailSender.send(new EmailMessage(event.memberEmail(), "Penalty of " + amount + " in " + event.chamaName(), """
                Hello %s,

                %s in %s: %s.

                Amount: %s

                Pay it to your chama's treasurer. If you think this is a mistake, ask a chama official
                to review it.
                """.formatted(event.memberFirstName() == null ? "member" : event.memberFirstName(), why,
                event.chamaName(), event.description(), amount)));
    }
}
