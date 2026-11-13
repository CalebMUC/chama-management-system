package ke.chama.modules.dataimport.domain.model.history;

import ke.chama.modules.dataimport.domain.model.Problem;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Column;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Row;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.AMOUNT;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.DATE;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.TEXT;

/**
 * Step 7: the chama's real cash, bank and M-Pesa balances on a day, to compare with what the
 * imported records add up to. Any difference (money held before the records start, bank interest or
 * charges nobody wrote down, a mistake) is posted as one adjustment, so the books match the bank.
 */
public final class ReconciliationCheck {

    public static final String BALANCES = "Balances";
    public static final String ACCOUNT = "Account", AS_AT = "As at", ACTUAL = "Actual balance";
    public static final List<String> ACCOUNTS = List.of("Cash on hand", "Bank account", "M-Pesa");

    private ReconciliationCheck() {
    }

    public static SheetSpec sheet() {
        return SheetSpec.data(BALANCES, true,
                Column.required(ACCOUNT, TEXT, 18, "Cash on hand, Bank account or M-Pesa").choosing(ACCOUNTS),
                Column.required(AS_AT, DATE, 14, "The day of the balance, e.g. the date of the last bank statement"),
                Column.required(ACTUAL, AMOUNT, 18, "What the account really held at the end of that day "
                        + "(from the bank statement, M-Pesa statement or a cash count)"));
    }

    /** @param account CASH, BANK or MOBILE_MONEY */
    public record Balance(int row, String account, LocalDate asAt, long actualMinor) {
    }

    public record Result(List<Balance> balances, List<Problem> problems) {
    }

    public static Result check(Workbook workbook, HistoryChama chama) {
        List<Problem> problems = new ArrayList<>(workbook.problems());
        List<Balance> balances = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Row r : workbook.rows(BALANCES)) {
            int before = problems.size();
            String account = account(r.text(ACCOUNT));
            if (account == null) {
                problems.add(Problem.error(BALANCES, r.row(), ACCOUNT, (r.text(ACCOUNT) == null ? "The account is required"
                        : "Unknown account " + r.text(ACCOUNT)) + "; use Cash on hand, Bank account or M-Pesa"));
            }
            LocalDate asAt = r.date(AS_AT);
            if (asAt == null && !r.unreadable(AS_AT)) {
                problems.add(Problem.error(BALANCES, r.row(), AS_AT, "The date is required"));
            } else if (asAt != null && asAt.isAfter(chama.today())) {
                problems.add(Problem.error(BALANCES, r.row(), AS_AT, "Cannot be in the future"));
            }
            Long actual = r.amount(ACTUAL);
            if (actual == null && !r.unreadable(ACTUAL)) {
                problems.add(Problem.error(BALANCES, r.row(), ACTUAL, "The balance is required (0 if empty)"));
            }
            if (problems.size() != before || asAt == null || actual == null) {
                continue;
            }
            if (!seen.add(account + "/" + asAt)) {
                problems.add(Problem.error(BALANCES, r.row(), ACCOUNT, r.text(ACCOUNT) + " on " + asAt
                        + " appears more than once"));
                continue;
            }
            balances.add(new Balance(r.row(), account, asAt, actual));
        }
        if (workbook.rows(BALANCES).isEmpty()) {
            problems.add(Problem.error(BALANCES, null, null, "Add at least one balance"));
        }
        return new Result(balances, problems);
    }

    static String account(String text) {
        String a = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (a) {
            case "cash", "cashonhand" -> "CASH";
            case "bank", "bankaccount" -> "BANK";
            case "mpesa", "mobilemoney", "mobilemoneympesa" -> "MOBILE_MONEY";
            default -> null;
        };
    }

    public static String label(String account) {
        return switch (account) {
            case "CASH" -> "Cash on hand";
            case "BANK" -> "Bank account";
            default -> "M-Pesa";
        };
    }
}
