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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.AMOUNT;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.DATE;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.TEXT;

/** Step 6: what the chama spent over the years, one row per payment. */
public final class ExpenseCheck {

    public static final String EXPENSES = "Expenses";

    public static final String PAID_ON = "Paid on", PAYEE = "Paid to", DESCRIPTION = "What for",
            AMOUNT_PAID = "Amount", ACCOUNT = "Expense account", METHOD = "Method", REFERENCE = "Reference",
            INVESTMENT = "Investment";

    private ExpenseCheck() {
    }

    /** An expense account in the chart of accounts. */
    public record AccountRef(String code, String name, boolean defaultAccount) {

        public String label() {
            return code + " - " + name;
        }
    }

    /** An investment an expense can be charged to. */
    public record InvestmentRef(UUID id, String name, LocalDate fundedOn) {
    }

    public static SheetSpec sheet(List<AccountRef> accounts, List<InvestmentRef> investments) {
        String main = accounts.stream().filter(AccountRef::defaultAccount).map(AccountRef::name).findFirst()
                .orElse("operating expenses");
        return SheetSpec.data(EXPENSES, true,
                Column.required(PAID_ON, DATE, 14, "The day it was paid"),
                Column.required(PAYEE, TEXT, 24, "Who was paid, e.g. KCB, the venue, a member"),
                Column.required(DESCRIPTION, TEXT, 30, "e.g. Bank charges for March; AGM venue"),
                Column.required(AMOUNT_PAID, AMOUNT, 14, "What was paid"),
                Column.optional(ACCOUNT, TEXT, 26, "Which expense account (" + main + " if blank)")
                        .choosing(accounts.stream().map(AccountRef::label).toList()),
                Column.optional(METHOD, TEXT, 12, "Cash, Bank or M-Pesa (Cash if blank)").choosing(HistoryChama.METHODS),
                Column.optional(REFERENCE, TEXT, 18, "M-Pesa code or bank slip number, if you have it"),
                Column.optional(INVESTMENT, TEXT, 26, "If it was spent on an investment (e.g. its rent or repairs): "
                        + "its name, as imported in step 5").choosing(investments.stream().map(InvestmentRef::name).toList()));
    }

    public record PlannedExpense(int row, LocalDate paidOn, String payee, String description, long amountMinor,
                                 AccountRef account, String method, String reference, InvestmentRef investment) {
    }

    public record Result(List<PlannedExpense> expenses, List<Problem> problems) {
    }

    public static Result check(Workbook workbook, HistoryChama chama, List<AccountRef> accounts,
                               List<InvestmentRef> investments) {
        List<Problem> problems = new ArrayList<>(workbook.problems());
        Set<String> references = new HashSet<>(chama.usedReferences());
        AccountRef main = accounts.stream().filter(AccountRef::defaultAccount).findFirst()
                .orElse(accounts.isEmpty() ? null : accounts.getFirst());
        int withoutReference = 0;
        List<PlannedExpense> expenses = new ArrayList<>();
        for (Row r : workbook.rows(EXPENSES)) {
            int before = problems.size();
            LocalDate paidOn = r.date(PAID_ON);
            if (paidOn == null && !r.unreadable(PAID_ON)) {
                problems.add(Problem.error(EXPENSES, r.row(), PAID_ON, "The date paid is required"));
            } else if (paidOn != null && paidOn.isAfter(chama.today())) {
                problems.add(Problem.error(EXPENSES, r.row(), PAID_ON, "Cannot be in the future"));
            }
            String payee = r.text(PAYEE);
            if (payee == null || payee.length() > 120) {
                problems.add(Problem.error(EXPENSES, r.row(), PAYEE, "Required, at most 120 characters"));
            }
            String description = r.text(DESCRIPTION);
            if (description == null || description.length() > 300) {
                problems.add(Problem.error(EXPENSES, r.row(), DESCRIPTION, "Required, at most 300 characters"));
            }
            Long amount = r.amount(AMOUNT_PAID);
            if ((amount == null && !r.unreadable(AMOUNT_PAID)) || (amount != null && amount <= 0)) {
                problems.add(Problem.error(EXPENSES, r.row(), AMOUNT_PAID, "Must be greater than zero"));
            }
            AccountRef account = main;
            if (r.text(ACCOUNT) != null) {
                account = account(accounts, r.text(ACCOUNT)).orElse(null);
                if (account == null) {
                    problems.add(Problem.error(EXPENSES, r.row(), ACCOUNT, "Unknown expense account " + r.text(ACCOUNT)
                            + ". Pick one from the list, or add it to the chart of accounts first."));
                }
            } else if (account == null) {
                problems.add(Problem.error(EXPENSES, r.row(), ACCOUNT, "The chama has no expense account"));
            }
            String method = "CASH";
            if (r.text(METHOD) != null) {
                method = HistoryChama.method(r.text(METHOD)).orElse(null);
                if (method == null) {
                    problems.add(Problem.error(EXPENSES, r.row(), METHOD, "Unknown method " + r.text(METHOD)
                            + "; use Cash, Bank or M-Pesa"));
                }
            }
            InvestmentRef investment = null;
            if (r.text(INVESTMENT) != null) {
                String name = r.text(INVESTMENT).trim();
                List<InvestmentRef> matches = investments.stream().filter(i -> i.name().equalsIgnoreCase(name)).toList();
                if (matches.isEmpty()) {
                    problems.add(Problem.error(EXPENSES, r.row(), INVESTMENT, "No investment called " + name));
                } else if (matches.size() > 1) {
                    problems.add(Problem.error(EXPENSES, r.row(), INVESTMENT, "More than one investment is called "
                            + name + "; rename one in the system first"));
                } else {
                    investment = matches.getFirst();
                    if (paidOn != null && paidOn.isBefore(investment.fundedOn())) {
                        problems.add(Problem.warning(EXPENSES, r.row(), INVESTMENT, "Paid before " + name
                                + " was invested in (" + investment.fundedOn() + ")"));
                    }
                }
            }
            String reference = r.text(REFERENCE) == null ? null : r.text(REFERENCE).trim().toUpperCase(Locale.ROOT);
            if (reference != null && reference.length() > 100) {
                problems.add(Problem.error(EXPENSES, r.row(), REFERENCE, "At most 100 characters"));
            } else if (reference != null && method != null && !references.add(method + ":" + reference)) {
                problems.add(Problem.error(EXPENSES, r.row(), REFERENCE, reference
                        + " is already used (in this file or in the system)"));
            }
            if (problems.size() != before || paidOn == null || amount == null) {
                continue;
            }
            if (reference == null && !method.equals("CASH")) {
                withoutReference++;
            }
            expenses.add(new PlannedExpense(r.row(), paidOn, payee.trim(), description.trim(), amount, account, method,
                    reference, investment));
        }
        if (withoutReference > 0) {
            problems.add(Problem.warning(EXPENSES, null, REFERENCE, withoutReference + " bank or M-Pesa payment"
                    + (withoutReference == 1 ? " has" : "s have") + " no reference. Each gets one made up from the "
                    + "import so it can still be told apart."));
        }
        if (workbook.rows(EXPENSES).isEmpty()) {
            problems.add(Problem.error(EXPENSES, null, null, "The file has no expenses to import"));
        }
        return new Result(expenses, problems);
    }

    private static Optional<AccountRef> account(List<AccountRef> accounts, String text) {
        String t = text.trim();
        String code = t.split("\\s+-\\s+|\\s+", 2)[0];
        return accounts.stream().filter(a -> a.code().equalsIgnoreCase(code)).findFirst()
                .or(() -> accounts.stream().filter(a -> a.name().equalsIgnoreCase(t)).findFirst());
    }

    public static String madeUpReference(String batchId, int row) {
        return "IMP-" + batchId.replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT) + "-E" + row;
    }
}
