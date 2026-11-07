package ke.chama.modules.dataimport.domain.model;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Currency;

/** Amount formatting for messages, e.g. "KES 5,000.00". */
final class Amounts {

    private Amounts() {
    }

    static String format(long minor, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return currency + " " + new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits))
                .format(BigDecimal.valueOf(minor, digits));
    }
}
