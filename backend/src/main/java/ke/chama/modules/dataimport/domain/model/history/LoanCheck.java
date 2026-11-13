package ke.chama.modules.dataimport.domain.model.history;

import ke.chama.modules.dataimport.domain.model.Problem;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.MemberRef;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Column;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Row;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.AMOUNT;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.DATE;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.INTEGER;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.TEXT;

/**
 * Step 3: every loan since the chama started, repaid or not, and every repayment. Interest is flat:
 * a share of the principal for the whole term, repaid with it in equal monthly instalments.
 */
public final class LoanCheck {

    public static final String LOANS = "Loans";
    public static final String REPAYMENTS = "Repayments";

    public static final String LOAN_REF = "Loan ref", MEMBER = "Member", DISBURSED_ON = "Disbursed on",
            PRINCIPAL = "Principal", INTEREST = "Interest", TERM = "Term (months)", PRODUCT = "Loan product",
            PURPOSE = "Purpose", METHOD = "Method", REFERENCE = "Reference";
    public static final String PAID_ON = "Paid on", AMOUNT_PAID = "Amount";

    static final int MAX_TERM_MONTHS = 36;
    static final int MAX_TEXT_LENGTH = 300;

    private LoanCheck() {
    }

    /** A loan product as the checks see it. */
    public record LoanProductRef(UUID id, String code, String name, int interestBasisPoints, int maxTermMonths,
                                 boolean defaultProduct, boolean active) {

        public String label() {
            return code + " - " + name;
        }

        /** Flat interest on {@code principalMinor} at this product's rate, rounded half up. */
        public long interestOn(long principalMinor) {
            return BigDecimal.valueOf(principalMinor).multiply(BigDecimal.valueOf(interestBasisPoints))
                    .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
        }

        public String rate() {
            return BigDecimal.valueOf(interestBasisPoints, 2).stripTrailingZeros().toPlainString() + "%";
        }
    }

    public static List<SheetSpec> sheets(List<String> memberLabels, List<LoanProductRef> products) {
        LoanProductRef main = products.stream().filter(LoanProductRef::defaultProduct).findFirst().orElse(null);
        String rate = main == null ? "the product's rate" : main.rate() + " for " + main.name();
        return List.of(
                SheetSpec.data(LOANS, true,
                        Column.required(LOAN_REF, TEXT, 12, "Any short name you choose, unique per loan, e.g. L001. "
                                + "Repayments use it to say which loan they repay."),
                        Column.required(MEMBER, TEXT, 28, "The borrower").choosing(memberLabels),
                        Column.required(DISBURSED_ON, DATE, 14, "The day the money was paid out"),
                        Column.required(PRINCIPAL, AMOUNT, 16, "The amount borrowed"),
                        Column.optional(INTEREST, AMOUNT, 14, "The flat interest for the whole term. Leave blank to "
                                + "use the product's rate (" + rate + ") on the principal"),
                        Column.required(TERM, INTEGER, 14, "Months to repay, 1 to " + MAX_TERM_MONTHS),
                        Column.optional(PRODUCT, TEXT, 22, "Leave blank for the chama's main loan product")
                                .choosing(products.stream().filter(LoanProductRef::active).map(LoanProductRef::label).toList()),
                        Column.optional(PURPOSE, TEXT, 26, "e.g. School fees"),
                        Column.optional(METHOD, TEXT, 12, "How it was paid out: Cash, Bank or M-Pesa (Cash if blank)")
                                .choosing(HistoryChama.METHODS),
                        Column.optional(REFERENCE, TEXT, 18, "M-Pesa code or bank slip number, if you have it")),
                SheetSpec.data(REPAYMENTS, false,
                        Column.required(LOAN_REF, TEXT, 12, "The loan this repays, as in the Loans sheet"),
                        Column.required(PAID_ON, DATE, 14, "The day the money was received"),
                        Column.required(AMOUNT_PAID, AMOUNT, 16, "What was repaid"),
                        Column.required(METHOD, TEXT, 12, "Cash, Bank or M-Pesa").choosing(HistoryChama.METHODS),
                        Column.optional(REFERENCE, TEXT, 18, "M-Pesa code or bank slip number, if you have it")));
    }

    public record Repayment(int row, LocalDate paidOn, long amountMinor, String method, String reference) {
    }

    /**
     * @param interestFromRate the interest was left blank and worked out from the product's rate
     * @param reference        upper case; null when the file has none
     */
    public record PlannedLoan(int row, String ref, MemberRef member, LoanProductRef product, LocalDate disbursedOn,
                              long principalMinor, long interestMinor, boolean interestFromRate, int termMonths,
                              String purpose, String method, String reference, List<Repayment> repayments) {

        public long totalMinor() {
            return principalMinor + interestMinor;
        }

        public long repaidMinor() {
            return repayments.stream().mapToLong(Repayment::amountMinor).sum();
        }

        public long outstandingMinor() {
            return totalMinor() - repaidMinor();
        }
    }

    public record Result(List<PlannedLoan> loans, int repaymentRows, List<Problem> problems) {
    }

    /**
     * @param products  the chama's loan products
     * @param openLoans loans in progress in the system now: borrower to products
     */
    public static Result check(Workbook workbook, HistoryChama chama, List<LoanProductRef> products,
                               Map<UUID, Set<UUID>> openLoans) {
        List<Problem> problems = new ArrayList<>(workbook.problems());
        LoanProductRef main = products.stream().filter(LoanProductRef::defaultProduct).findFirst().orElseThrow();
        Set<String> references = new HashSet<>(chama.usedReferences());
        int[] withoutReference = {0};

        Map<String, PlannedLoan> byRef = new LinkedHashMap<>();
        Set<String> refsSeen = new HashSet<>();
        for (Row r : workbook.rows(LOANS)) {
            int before = problems.size();
            String ref = key(r.text(LOAN_REF));
            if (ref == null) {
                problems.add(Problem.error(LOANS, r.row(), LOAN_REF, "A loan ref is required, e.g. L001"));
            } else if (!refsSeen.add(ref)) {
                problems.add(Problem.error(LOANS, r.row(), LOAN_REF, r.text(LOAN_REF) + " appears more than once"));
            }
            Optional<MemberRef> member = chama.member(r.text(MEMBER));
            if (member.isEmpty()) {
                problems.add(Problem.error(LOANS, r.row(), MEMBER, r.text(MEMBER) == null
                        ? "The borrower is required" : "No member " + r.text(MEMBER) + " in the chama"));
            }
            LocalDate disbursed = r.date(DISBURSED_ON);
            if (disbursed == null && !r.unreadable(DISBURSED_ON)) {
                problems.add(Problem.error(LOANS, r.row(), DISBURSED_ON, "The day the loan was paid out is required"));
            } else if (disbursed != null && disbursed.isAfter(chama.today())) {
                problems.add(Problem.error(LOANS, r.row(), DISBURSED_ON, "Cannot be in the future"));
            }
            Long principal = r.amount(PRINCIPAL);
            if ((principal == null && !r.unreadable(PRINCIPAL)) || (principal != null && principal <= 0)) {
                problems.add(Problem.error(LOANS, r.row(), PRINCIPAL, "The amount borrowed must be greater than zero"));
            }
            LoanProductRef product = main;
            if (r.text(PRODUCT) != null) {
                product = product(products, r.text(PRODUCT)).orElse(null);
                if (product == null) {
                    problems.add(Problem.error(LOANS, r.row(), PRODUCT, "Unknown loan product " + r.text(PRODUCT)));
                }
            }
            Integer term = r.integer(TERM);
            if ((term == null && !r.unreadable(TERM)) || (term != null && (term < 1 || term > MAX_TERM_MONTHS))) {
                problems.add(Problem.error(LOANS, r.row(), TERM, "Must be between 1 and " + MAX_TERM_MONTHS + " months"));
            }
            Long interest = r.amount(INTEREST);
            if (interest != null && interest < 0) {
                problems.add(Problem.error(LOANS, r.row(), INTEREST, "Cannot be negative"));
            }
            String method = "CASH";
            if (r.text(METHOD) != null) {
                method = HistoryChama.method(r.text(METHOD)).orElse(null);
                if (method == null) {
                    problems.add(Problem.error(LOANS, r.row(), METHOD, "Unknown method " + r.text(METHOD)
                            + "; use Cash, Bank or M-Pesa"));
                }
            }
            String purpose = r.text(PURPOSE);
            if (purpose != null && purpose.length() > MAX_TEXT_LENGTH) {
                problems.add(Problem.error(LOANS, r.row(), PURPOSE, "At most " + MAX_TEXT_LENGTH + " characters"));
            }
            String reference = reference(r, LOANS, method, references, withoutReference, problems);
            if (problems.size() != before || principal == null || term == null || disbursed == null) {
                continue;
            }
            boolean fromRate = interest == null;
            long interestMinor = fromRate ? product.interestOn(principal) : interest;
            if (!fromRate && interestMinor != product.interestOn(principal)) {
                problems.add(Problem.warning(LOANS, r.row(), INTEREST, "The interest is " + chama.format(interestMinor)
                        + "; " + product.rate() + " of the principal would be " + chama.format(product.interestOn(principal))
                        + ". The loan keeps the interest in the file."));
            }
            if (term > product.maxTermMonths()) {
                problems.add(Problem.warning(LOANS, r.row(), TERM, product.name() + " now allows at most "
                        + product.maxTermMonths() + " months; this loan keeps its " + term));
            }
            MemberRef m = member.get();
            if (m.joinedOn() != null && disbursed.isBefore(m.joinedOn())) {
                problems.add(Problem.warning(LOANS, r.row(), DISBURSED_ON, m.name() + " joined on " + m.joinedOn()
                        + "; this loan was paid out earlier."));
            }
            byRef.put(ref, new PlannedLoan(r.row(), r.text(LOAN_REF).trim(), m, product, disbursed, principal,
                    interestMinor, fromRate, term, purpose, method, reference, new ArrayList<>()));
        }

        // Repayments, oldest first per loan, never more than the loan's total.
        List<Row> repayments = new ArrayList<>(workbook.rows(REPAYMENTS));
        Map<String, List<Repayment>> repaid = new HashMap<>();
        for (Row r : repayments) {
            int before = problems.size();
            String ref = key(r.text(LOAN_REF));
            PlannedLoan loan = ref == null ? null : byRef.get(ref);
            if (ref == null) {
                problems.add(Problem.error(REPAYMENTS, r.row(), LOAN_REF, "Say which loan this repays"));
            } else if (loan == null) {
                if (!refsSeen.contains(ref)) {
                    problems.add(Problem.error(REPAYMENTS, r.row(), LOAN_REF, "No loan " + r.text(LOAN_REF)
                            + " in the Loans sheet"));
                }
                continue;
            }
            LocalDate paidOn = r.date(PAID_ON);
            if (paidOn == null && !r.unreadable(PAID_ON)) {
                problems.add(Problem.error(REPAYMENTS, r.row(), PAID_ON, "The date paid is required"));
            } else if (paidOn != null && paidOn.isAfter(chama.today())) {
                problems.add(Problem.error(REPAYMENTS, r.row(), PAID_ON, "Cannot be in the future"));
            } else if (paidOn != null && loan != null && paidOn.isBefore(loan.disbursedOn())) {
                problems.add(Problem.error(REPAYMENTS, r.row(), PAID_ON, "Before loan " + loan.ref() + " was paid out ("
                        + loan.disbursedOn() + ")"));
            }
            Long amount = r.amount(AMOUNT_PAID);
            if ((amount == null && !r.unreadable(AMOUNT_PAID)) || (amount != null && amount <= 0)) {
                problems.add(Problem.error(REPAYMENTS, r.row(), AMOUNT_PAID, "Must be greater than zero"));
            }
            Optional<String> method = HistoryChama.method(r.text(METHOD));
            if (method.isEmpty()) {
                problems.add(Problem.error(REPAYMENTS, r.row(), METHOD, (r.text(METHOD) == null ? "The method is required"
                        : "Unknown method " + r.text(METHOD)) + "; use Cash, Bank or M-Pesa"));
            }
            String reference = reference(r, REPAYMENTS, method.orElse("CASH"), references, withoutReference, problems);
            if (problems.size() != before || paidOn == null || amount == null || loan == null) {
                continue;
            }
            repaid.computeIfAbsent(ref, k -> new ArrayList<>())
                    .add(new Repayment(r.row(), paidOn, amount, method.get(), reference));
        }
        List<PlannedLoan> loans = new ArrayList<>();
        for (Map.Entry<String, PlannedLoan> e : byRef.entrySet()) {
            PlannedLoan loan = e.getValue();
            List<Repayment> list = repaid.getOrDefault(e.getKey(), List.of()).stream()
                    .sorted(Comparator.comparing(Repayment::paidOn).thenComparingInt(Repayment::row)).toList();
            long sum = 0;
            boolean over = false;
            for (Repayment rp : list) {
                sum += rp.amountMinor();
                if (sum > loan.totalMinor() && !over) {
                    over = true;
                    problems.add(Problem.error(REPAYMENTS, rp.row(), AMOUNT_PAID, "Loan " + loan.ref() + " is "
                            + chama.format(loan.totalMinor()) + " with interest; repayments up to here add up to "
                            + chama.format(sum)));
                }
            }
            loans.add(new PlannedLoan(loan.row(), loan.ref(), loan.member(), loan.product(), loan.disbursedOn(),
                    loan.principalMinor(), loan.interestMinor(), loan.interestFromRate(), loan.termMonths(),
                    loan.purpose(), loan.method(), loan.reference(), list));
        }

        // A member repays one loan per product at a time.
        Map<String, PlannedLoan> open = new HashMap<>();
        for (PlannedLoan loan : loans.stream().sorted(Comparator.comparing(PlannedLoan::disbursedOn)).toList()) {
            if (loan.outstandingMinor() <= 0) {
                continue;
            }
            String key = loan.member().userId() + "/" + loan.product().id();
            PlannedLoan other = open.putIfAbsent(key, loan);
            if (other != null) {
                problems.add(Problem.error(LOANS, loan.row(), LOAN_REF, loan.member().name() + " would still owe on two "
                        + loan.product().name() + " loans (" + other.ref() + " and " + loan.ref() + "). A member repays "
                        + "one at a time: add the repayments that cleared " + other.ref() + ", or combine them."));
            } else if (openLoans.getOrDefault(loan.member().userId(), Set.of()).contains(loan.product().id())) {
                problems.add(Problem.error(LOANS, loan.row(), LOAN_REF, loan.member().name() + " already has a "
                        + loan.product().name() + " loan in the system, so this one must be fully repaid in the file"));
            }
        }
        if (withoutReference[0] > 0) {
            problems.add(Problem.warning(null, null, REFERENCE, withoutReference[0] + " bank or M-Pesa transaction"
                    + (withoutReference[0] == 1 ? " has" : "s have") + " no reference. Each gets one made up from the "
                    + "import (e.g. IMP-3F2A9C-L12 for row 12 of Loans) so it can still be told apart."));
        }
        if (workbook.rows(LOANS).isEmpty()) {
            problems.add(Problem.error(LOANS, null, null, "The file has no loans to import"));
        }
        return new Result(loans, repayments.size(), problems);
    }

    private static String reference(Row r, String sheet, String method, Set<String> used, int[] withoutReference,
                                    List<Problem> problems) {
        String reference = r.text(REFERENCE) == null ? null : r.text(REFERENCE).trim().toUpperCase(Locale.ROOT);
        if (reference == null) {
            if (method != null && !method.equals("CASH")) {
                withoutReference[0]++;
            }
            return null;
        }
        if (reference.length() > 100) {
            problems.add(Problem.error(sheet, r.row(), REFERENCE, "At most 100 characters"));
        } else if (method != null && !used.add(method + ":" + reference)) {
            problems.add(Problem.error(sheet, r.row(), REFERENCE, reference
                    + " is already used (in this file or in the system)"));
        }
        return reference;
    }

    private static Optional<LoanProductRef> product(List<LoanProductRef> products, String text) {
        String code = text.trim().split("\\s+-\\s+|\\s+", 2)[0];
        return products.stream().filter(p -> p.code().equalsIgnoreCase(code)).findFirst()
                .or(() -> products.stream().filter(p -> p.name().equalsIgnoreCase(text.trim())).findFirst());
    }

    private static String key(String ref) {
        return ref == null || ref.isBlank() ? null : ref.trim().toUpperCase(Locale.ROOT);
    }

    /** The reference given to a bank or M-Pesa transaction the records have none for. */
    public static String madeUpReference(String batchId, String sheet, int row) {
        return "IMP-" + batchId.replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT) + "-"
                + sheet.charAt(0) + row;
    }
}
