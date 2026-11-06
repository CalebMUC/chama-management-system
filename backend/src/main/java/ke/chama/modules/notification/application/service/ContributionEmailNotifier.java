package ke.chama.modules.notification.application.service;

import ke.chama.modules.contribution.domain.event.ContributionRecorded;
import ke.chama.modules.notification.application.port.out.EmailMessage;
import ke.chama.modules.notification.application.port.out.EmailSender;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.util.Currency;

/** A receipt for every recorded contribution, sent after the transaction commits. */
@Component
class ContributionEmailNotifier {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final EmailSender emailSender;

    ContributionEmailNotifier(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @ApplicationModuleListener
    void on(ContributionRecorded event) {
        String amount = format(event.amountMinor(), event.currency());
        String reference = event.reference() == null ? "" : " (ref. " + event.reference() + ")";
        String arrears = event.arrearsMinor() > 0
                ? "You still have " + format(event.arrearsMinor(), event.currency()) + " in arrears."
                : "You are up to date with your contributions.";
        emailSender.send(new EmailMessage(event.memberEmail(), "Contribution received: " + amount, """
                Hello %s,

                %s received your contribution of %s by %s%s on %s.

                Your savings with the chama are now %s.
                %s

                If this is not right, contact your chama's treasurer.
                """.formatted(event.memberFirstName() == null ? "member" : event.memberFirstName(), event.chamaName(),
                amount, method(event.method()), reference, DATE.format(event.paidOn()),
                format(event.savingsBalanceMinor(), event.currency()), arrears)));
    }

    private static String method(String method) {
        return switch (method) {
            case "MPESA" -> "M-Pesa";
            case "BANK" -> "bank transfer";
            default -> "cash";
        };
    }

    /** E.g. KES 5,000.00 */
    static String format(long minor, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode);
        int digits = Math.max(currency.getDefaultFractionDigits(), 0);
        DecimalFormat format = new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits));
        return currencyCode + " " + format.format(BigDecimal.valueOf(minor, digits));
    }
}
