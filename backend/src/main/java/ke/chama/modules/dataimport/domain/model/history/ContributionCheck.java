package ke.chama.modules.dataimport.domain.model.history;

import ke.chama.modules.dataimport.domain.model.Problem;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.MemberRef;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.ProductRef;
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

import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.AMOUNT;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.DATE;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.TEXT;

/** Step 2: every contribution a member paid, one row per payment. */
public final class ContributionCheck {

    public static final String CONTRIBUTIONS = "Contributions";

    public static final String MEMBER = "Member", PAID_ON = "Paid on", AMOUNT_PAID = "Amount", METHOD = "Method",
            REFERENCE = "Reference", PRODUCT = "Savings product", NOTES = "Notes";

    static final int MAX_REFERENCE_LENGTH = 100;
    static final int MAX_NOTES_LENGTH = 300;

    private ContributionCheck() {
    }

    public static SheetSpec sheet(List<String> memberLabels, List<String> productLabels) {
        return SheetSpec.data(CONTRIBUTIONS, true,
                Column.required(MEMBER, TEXT, 28, "Pick from the list, or type the member number").choosing(memberLabels),
                Column.required(PAID_ON, DATE, 14, "The day the money was received"),
                Column.required(AMOUNT_PAID, AMOUNT, 16, "What the member paid"),
                Column.required(METHOD, TEXT, 12, "Cash, Bank or M-Pesa").choosing(HistoryChama.METHODS),
                Column.optional(REFERENCE, TEXT, 18, "M-Pesa code or bank slip number, if you have it"),
                Column.optional(PRODUCT, TEXT, 26, "Leave blank to split it like the system does (oldest dues first); "
                        + "or name the one product it all went to").choosing(productLabels),
                Column.optional(NOTES, TEXT, 30, "Anything worth keeping, e.g. \"paid for March and April\""));
    }

    /**
     * @param reference upper case; null when the file has none (cash, or records without it)
     * @param product   null to split it automatically
     */
    public record Payment(int row, MemberRef member, LocalDate paidOn, long amountMinor, String method,
                          String reference, ProductRef product, String notes) {
    }

    public record Result(List<Payment> payments, List<Problem> problems) {

        public boolean importable() {
            return problems.stream().noneMatch(Problem::blocking);
        }
    }

    public static Result check(Workbook workbook, HistoryChama chama) {
        List<Problem> problems = new ArrayList<>(workbook.problems());
        List<Payment> payments = new ArrayList<>();
        Set<String> references = new HashSet<>();
        int withoutReference = 0;
        for (Row r : workbook.rows(CONTRIBUTIONS)) {
            int before = problems.size();
            Optional<MemberRef> member = chama.member(r.text(MEMBER));
            if (member.isEmpty()) {
                problems.add(Problem.error(CONTRIBUTIONS, r.row(), MEMBER, r.text(MEMBER) == null
                        ? "The member is required" : "No member " + r.text(MEMBER) + " in the chama"));
            }
            LocalDate paidOn = r.date(PAID_ON);
            if (paidOn == null && !r.unreadable(PAID_ON)) {
                problems.add(Problem.error(CONTRIBUTIONS, r.row(), PAID_ON, "The date paid is required"));
            } else if (paidOn != null && paidOn.isAfter(chama.today())) {
                problems.add(Problem.error(CONTRIBUTIONS, r.row(), PAID_ON, "Cannot be in the future"));
            }
            Long amount = r.amount(AMOUNT_PAID);
            if ((amount == null && !r.unreadable(AMOUNT_PAID)) || (amount != null && amount <= 0)) {
                problems.add(Problem.error(CONTRIBUTIONS, r.row(), AMOUNT_PAID, "Must be greater than zero"));
            }
            Optional<String> method = HistoryChama.method(r.text(METHOD));
            if (method.isEmpty()) {
                problems.add(Problem.error(CONTRIBUTIONS, r.row(), METHOD, (r.text(METHOD) == null ? "The method is required"
                        : "Unknown method " + r.text(METHOD)) + "; use Cash, Bank or M-Pesa"));
            }
            String reference = r.text(REFERENCE) == null ? null : r.text(REFERENCE).trim().toUpperCase(Locale.ROOT);
            if (reference != null && reference.length() > MAX_REFERENCE_LENGTH) {
                problems.add(Problem.error(CONTRIBUTIONS, r.row(), REFERENCE, "At most " + MAX_REFERENCE_LENGTH + " characters"));
            }
            ProductRef product = null;
            if (r.text(PRODUCT) != null) {
                product = chama.savingsProduct(r.text(PRODUCT)).orElse(null);
                if (product == null) {
                    problems.add(Problem.error(CONTRIBUTIONS, r.row(), PRODUCT, "Unknown savings product " + r.text(PRODUCT)));
                } else if (!product.active()) {
                    problems.add(Problem.error(CONTRIBUTIONS, r.row(), PRODUCT, product.label() + " is no longer offered"));
                } else if (member.isPresent() && !member.get().openProductIds().contains(product.id())) {
                    problems.add(Problem.error(CONTRIBUTIONS, r.row(), PRODUCT, member.get().name()
                            + " has no open account in " + product.label() + ". Open it in the system first."));
                }
            }
            String notes = r.text(NOTES);
            if (notes != null && notes.length() > MAX_NOTES_LENGTH) {
                problems.add(Problem.error(CONTRIBUTIONS, r.row(), NOTES, "At most " + MAX_NOTES_LENGTH + " characters"));
            }
            if (problems.size() != before || paidOn == null || amount == null) {
                continue;
            }
            if (reference != null) {
                String key = method.get() + ":" + reference;
                if (chama.usedReferences().contains(key)) {
                    problems.add(Problem.error(CONTRIBUTIONS, r.row(), REFERENCE, reference
                            + " is already recorded in the system: this payment may be in it already"));
                    continue;
                }
                if (!references.add(key)) {
                    problems.add(Problem.error(CONTRIBUTIONS, r.row(), REFERENCE, reference + " appears more than once"));
                    continue;
                }
            } else if (!method.get().equals("CASH")) {
                withoutReference++;
            }
            MemberRef m = member.get();
            if (m.joinedOn() != null && paidOn.isBefore(m.joinedOn())) {
                problems.add(Problem.warning(CONTRIBUTIONS, r.row(), PAID_ON, m.name() + " joined on " + m.joinedOn()
                        + "; this payment is earlier. If they joined earlier, correct it in the setup import first."));
            }
            payments.add(new Payment(r.row(), m, paidOn, amount, method.get(), reference, product, notes));
        }
        if (withoutReference > 0) {
            problems.add(Problem.warning(CONTRIBUTIONS, null, REFERENCE, withoutReference + " bank or M-Pesa payment"
                    + (withoutReference == 1 ? " has" : "s have") + " no reference. Each gets one made up from the "
                    + "import (e.g. IMP-3F2A9C-17 for row 17) so it can still be told apart."));
        }
        if (workbook.rows(CONTRIBUTIONS).isEmpty()) {
            problems.add(Problem.error(CONTRIBUTIONS, null, null, "The file has no contributions to import"));
        }
        return new Result(payments, problems);
    }

    /** The reference given to a bank or M-Pesa payment the records have none for. */
    public static String madeUpReference(String batchId, int row) {
        return "IMP-" + batchId.replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT) + "-" + row;
    }
}
