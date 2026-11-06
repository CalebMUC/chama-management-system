package ke.chama.modules.notification.application.service;

import ke.chama.modules.loan.domain.event.LoanGuarantorRequested;
import ke.chama.modules.loan.domain.event.LoanStatusChanged;
import ke.chama.modules.notification.application.port.out.EmailMessage;
import ke.chama.modules.notification.application.port.out.EmailSender;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

import static ke.chama.modules.notification.application.service.ContributionEmailNotifier.format;

/** Asks guarantors to respond, and tells borrowers how their loan is going. Sent after the transaction commits. */
@Component
class LoanEmailNotifier {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final EmailSender emailSender;

    LoanEmailNotifier(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @ApplicationModuleListener
    void on(LoanGuarantorRequested event) {
        String amount = format(event.principalMinor(), event.currency());
        emailSender.send(new EmailMessage(event.guarantorEmail(),
                event.borrowerName() + " asked you to guarantee a loan in " + event.chamaName(), """
                Hello %s,

                %s has applied for a loan of %s over %d month%s in %s, for: %s.

                They named you as a guarantor. If they do not repay, the chama may recover the loan from
                guarantors' savings. Open the app to accept or decline.
                """.formatted(name(event.guarantorFirstName()), event.borrowerName(), amount, event.termMonths(),
                event.termMonths() == 1 ? "" : "s", event.chamaName(), event.purpose())));
    }

    @ApplicationModuleListener
    void on(LoanStatusChanged event) {
        String principal = format(event.principalMinor(), event.currency());
        String subject;
        String body;
        switch (event.status()) {
            case "APPROVED" -> {
                subject = "Your loan of " + principal + " was approved";
                body = "Your loan of %s in %s was approved. The treasurer will disburse it."
                        .formatted(principal, event.chamaName());
            }
            case "REJECTED" -> {
                subject = "Your loan application in " + event.chamaName() + " was not approved";
                body = "Your application for %s was not approved. Reason: %s".formatted(principal, event.reason());
            }
            case "ACTIVE" -> {
                subject = "Your loan of " + principal + " was disbursed";
                body = """
                        %s disbursed your loan of %s. You will repay %s in total.

                        Your first instalment of %s is due on %s.""".formatted(event.chamaName(), principal,
                        format(event.totalDueMinor(), event.currency()), format(event.instalmentMinor(), event.currency()),
                        event.firstDueDate() == null ? "-" : DATE.format(event.firstDueDate()));
            }
            case "REPAID" -> {
                subject = "Your loan in " + event.chamaName() + " is fully repaid";
                body = "Thank you. Your loan of %s is fully repaid (%s in total)."
                        .formatted(principal, format(event.totalDueMinor(), event.currency()));
            }
            default -> {
                return;
            }
        }
        emailSender.send(new EmailMessage(event.borrowerEmail(), subject, """
                Hello %s,

                %s

                If anything here is not right, contact a chama official.
                """.formatted(name(event.borrowerFirstName()), body)));
    }

    private static String name(String firstName) {
        return firstName == null ? "member" : firstName;
    }
}
