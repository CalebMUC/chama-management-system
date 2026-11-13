package ke.chama.modules.dataimport.domain.model.history;

import ke.chama.modules.dataimport.domain.model.Problem;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.MemberRef;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Column;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Row;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;

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

import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.AMOUNT;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.DATE;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.TEXT;

/**
 * Step 4: the penalties and fines the chama charged over the years, with what was paid and what
 * was waived. The system never works out penalties for those years itself.
 */
public final class PenaltyCheck {

    public static final String PENALTIES = "Penalties";
    public static final String PAYMENTS = "Penalty payments";

    public static final String PENALTY_REF = "Penalty ref", MEMBER = "Member", DATE_CHARGED = "Date",
            AMOUNT_CHARGED = "Amount", TYPE = "Type", REASON = "Reason", WAIVED_ON = "Waived on",
            WAIVER_REASON = "Waiver reason";
    public static final String PAID_ON = "Paid on", AMOUNT_PAID = "Amount", METHOD = "Method", REFERENCE = "Reference";

    public static final List<String> TYPES = List.of("Late contribution", "Fine");
    static final int MAX_TEXT_LENGTH = 300;

    private PenaltyCheck() {
    }

    public static List<SheetSpec> sheets(List<String> memberLabels) {
        return List.of(
                SheetSpec.data(PENALTIES, true,
                        Column.required(PENALTY_REF, TEXT, 12, "Any short name you choose, unique per penalty, e.g. P001"),
                        Column.required(MEMBER, TEXT, 28, "Who was charged").choosing(memberLabels),
                        Column.required(DATE_CHARGED, DATE, 14, "The day it was charged"),
                        Column.required(AMOUNT_CHARGED, AMOUNT, 14, "What was charged"),
                        Column.optional(TYPE, TEXT, 18, "Late contribution or Fine (Fine if blank)").choosing(TYPES),
                        Column.optional(REASON, TEXT, 34, "e.g. Missed the March meeting; or Late contribution for 2024-03"),
                        Column.optional(WAIVED_ON, DATE, 14, "If what was left unpaid was waived, the day it was"),
                        Column.optional(WAIVER_REASON, TEXT, 30, "Why it was waived")),
                SheetSpec.data(PAYMENTS, false,
                        Column.required(PENALTY_REF, TEXT, 12, "The penalty this pays, as in the Penalties sheet"),
                        Column.required(PAID_ON, DATE, 14, "The day the money was received"),
                        Column.required(AMOUNT_PAID, AMOUNT, 14, "What was paid"),
                        Column.required(METHOD, TEXT, 12, "Cash, Bank or M-Pesa").choosing(HistoryChama.METHODS),
                        Column.optional(REFERENCE, TEXT, 18, "M-Pesa code or bank slip number, if you have it")));
    }

    public record Payment(int row, LocalDate paidOn, long amountMinor, String method, String reference) {
    }

    /** @param kind LATE_CONTRIBUTION or FINE */
    public record PlannedPenalty(int row, String ref, MemberRef member, LocalDate chargedOn, long amountMinor,
                                 String kind, String reason, LocalDate waivedOn, String waiverReason,
                                 List<Payment> payments) {

        public long paidMinor() {
            return payments.stream().mapToLong(Payment::amountMinor).sum();
        }

        public long waivedMinor() {
            return waivedOn == null ? 0 : amountMinor - paidMinor();
        }

        public long outstandingMinor() {
            return amountMinor - paidMinor() - waivedMinor();
        }
    }

    public record Result(List<PlannedPenalty> penalties, int paymentRows, List<Problem> problems) {
    }

    public static Result check(Workbook workbook, HistoryChama chama) {
        List<Problem> problems = new ArrayList<>(workbook.problems());
        Set<String> references = new HashSet<>(chama.usedReferences());
        int withoutReference = 0;

        Map<String, PlannedPenalty> byRef = new LinkedHashMap<>();
        Set<String> refsSeen = new HashSet<>();
        for (Row r : workbook.rows(PENALTIES)) {
            int before = problems.size();
            String ref = key(r.text(PENALTY_REF));
            if (ref == null) {
                problems.add(Problem.error(PENALTIES, r.row(), PENALTY_REF, "A penalty ref is required, e.g. P001"));
            } else if (ref.length() > 40) {
                problems.add(Problem.error(PENALTIES, r.row(), PENALTY_REF, "At most 40 characters"));
            } else if (!refsSeen.add(ref)) {
                problems.add(Problem.error(PENALTIES, r.row(), PENALTY_REF, r.text(PENALTY_REF) + " appears more than once"));
            }
            Optional<MemberRef> member = chama.member(r.text(MEMBER));
            if (member.isEmpty()) {
                problems.add(Problem.error(PENALTIES, r.row(), MEMBER, r.text(MEMBER) == null
                        ? "The member is required" : "No member " + r.text(MEMBER) + " in the chama"));
            }
            LocalDate charged = r.date(DATE_CHARGED);
            if (charged == null && !r.unreadable(DATE_CHARGED)) {
                problems.add(Problem.error(PENALTIES, r.row(), DATE_CHARGED, "The date it was charged is required"));
            } else if (charged != null && charged.isAfter(chama.today())) {
                problems.add(Problem.error(PENALTIES, r.row(), DATE_CHARGED, "Cannot be in the future"));
            }
            Long amount = r.amount(AMOUNT_CHARGED);
            if ((amount == null && !r.unreadable(AMOUNT_CHARGED)) || (amount != null && amount <= 0)) {
                problems.add(Problem.error(PENALTIES, r.row(), AMOUNT_CHARGED, "Must be greater than zero"));
            }
            String kind = kind(r.text(TYPE));
            if (kind == null) {
                problems.add(Problem.error(PENALTIES, r.row(), TYPE, "Unknown type " + r.text(TYPE)
                        + "; use Late contribution or Fine"));
            }
            String reason = r.text(REASON);
            if (reason != null && reason.length() > MAX_TEXT_LENGTH) {
                problems.add(Problem.error(PENALTIES, r.row(), REASON, "At most " + MAX_TEXT_LENGTH + " characters"));
            }
            LocalDate waived = r.date(WAIVED_ON);
            if (waived != null && waived.isAfter(chama.today())) {
                problems.add(Problem.error(PENALTIES, r.row(), WAIVED_ON, "Cannot be in the future"));
            } else if (waived != null && charged != null && waived.isBefore(charged)) {
                problems.add(Problem.error(PENALTIES, r.row(), WAIVED_ON, "Before the penalty was charged (" + charged + ")"));
            }
            String waiverReason = r.text(WAIVER_REASON);
            if (waiverReason != null && waiverReason.length() > MAX_TEXT_LENGTH) {
                problems.add(Problem.error(PENALTIES, r.row(), WAIVER_REASON, "At most " + MAX_TEXT_LENGTH + " characters"));
            }
            if (waiverReason != null && waived == null && !r.unreadable(WAIVED_ON)) {
                problems.add(Problem.error(PENALTIES, r.row(), WAIVED_ON, "Say when it was waived"));
            }
            if (problems.size() != before || charged == null || amount == null) {
                continue;
            }
            String description = reason != null ? reason.trim()
                    : kind.equals("FINE") ? "Fine (from the chama's records)" : "Late contribution (from the chama's records)";
            MemberRef m = member.get();
            if (m.joinedOn() != null && charged.isBefore(m.joinedOn())) {
                problems.add(Problem.warning(PENALTIES, r.row(), DATE_CHARGED, m.name() + " joined on " + m.joinedOn()
                        + "; this penalty is earlier."));
            }
            byRef.put(ref, new PlannedPenalty(r.row(), r.text(PENALTY_REF).trim(), m, charged, amount, kind, description,
                    waived, waived == null ? null : waiverReason == null ? "Waived (from the chama's records)"
                    : waiverReason.trim(), List.of()));
        }

        int paymentRows = workbook.rows(PAYMENTS).size();
        Map<String, List<Payment>> paid = new HashMap<>();
        for (Row r : workbook.rows(PAYMENTS)) {
            int before = problems.size();
            String ref = key(r.text(PENALTY_REF));
            PlannedPenalty penalty = ref == null ? null : byRef.get(ref);
            if (ref == null) {
                problems.add(Problem.error(PAYMENTS, r.row(), PENALTY_REF, "Say which penalty this pays"));
                continue;
            } else if (penalty == null) {
                if (!refsSeen.contains(ref)) {
                    problems.add(Problem.error(PAYMENTS, r.row(), PENALTY_REF, "No penalty " + r.text(PENALTY_REF)
                            + " in the Penalties sheet"));
                }
                continue;
            }
            LocalDate paidOn = r.date(PAID_ON);
            if (paidOn == null && !r.unreadable(PAID_ON)) {
                problems.add(Problem.error(PAYMENTS, r.row(), PAID_ON, "The date paid is required"));
            } else if (paidOn != null && paidOn.isAfter(chama.today())) {
                problems.add(Problem.error(PAYMENTS, r.row(), PAID_ON, "Cannot be in the future"));
            } else if (paidOn != null && paidOn.isBefore(penalty.chargedOn())) {
                problems.add(Problem.error(PAYMENTS, r.row(), PAID_ON, "Before penalty " + penalty.ref()
                        + " was charged (" + penalty.chargedOn() + ")"));
            } else if (paidOn != null && penalty.waivedOn() != null && paidOn.isAfter(penalty.waivedOn())) {
                problems.add(Problem.error(PAYMENTS, r.row(), PAID_ON, "After penalty " + penalty.ref() + " was waived ("
                        + penalty.waivedOn() + ")"));
            }
            Long amount = r.amount(AMOUNT_PAID);
            if ((amount == null && !r.unreadable(AMOUNT_PAID)) || (amount != null && amount <= 0)) {
                problems.add(Problem.error(PAYMENTS, r.row(), AMOUNT_PAID, "Must be greater than zero"));
            }
            Optional<String> method = HistoryChama.method(r.text(METHOD));
            if (method.isEmpty()) {
                problems.add(Problem.error(PAYMENTS, r.row(), METHOD, (r.text(METHOD) == null ? "The method is required"
                        : "Unknown method " + r.text(METHOD)) + "; use Cash, Bank or M-Pesa"));
            }
            String reference = r.text(REFERENCE) == null ? null : r.text(REFERENCE).trim().toUpperCase(Locale.ROOT);
            if (reference != null && reference.length() > 100) {
                problems.add(Problem.error(PAYMENTS, r.row(), REFERENCE, "At most 100 characters"));
            } else if (reference != null && method.isPresent() && !references.add(method.get() + ":" + reference)) {
                problems.add(Problem.error(PAYMENTS, r.row(), REFERENCE, reference
                        + " is already used (in this file or in the system)"));
            }
            if (problems.size() != before || paidOn == null || amount == null) {
                continue;
            }
            if (reference == null && !method.get().equals("CASH")) {
                withoutReference++;
            }
            paid.computeIfAbsent(ref, k -> new ArrayList<>()).add(new Payment(r.row(), paidOn, amount, method.get(),
                    reference));
        }

        List<PlannedPenalty> penalties = new ArrayList<>();
        for (Map.Entry<String, PlannedPenalty> e : byRef.entrySet()) {
            PlannedPenalty p = e.getValue();
            List<Payment> payments = paid.getOrDefault(e.getKey(), List.of()).stream()
                    .sorted(Comparator.comparing(Payment::paidOn).thenComparingInt(Payment::row)).toList();
            long sum = 0;
            for (Payment payment : payments) {
                sum += payment.amountMinor();
                if (sum > p.amountMinor()) {
                    problems.add(Problem.error(PAYMENTS, payment.row(), AMOUNT_PAID, "Penalty " + p.ref() + " is "
                            + chama.format(p.amountMinor()) + "; payments up to here add up to " + chama.format(sum)));
                    break;
                }
            }
            if (p.waivedOn() != null && sum >= p.amountMinor()) {
                problems.add(Problem.error(PENALTIES, p.row(), WAIVED_ON, "Penalty " + p.ref()
                        + " was paid in full: there is nothing left to waive"));
            }
            penalties.add(new PlannedPenalty(p.row(), p.ref(), p.member(), p.chargedOn(), p.amountMinor(), p.kind(),
                    p.reason(), p.waivedOn(), p.waiverReason(), payments));
        }
        if (withoutReference > 0) {
            problems.add(Problem.warning(PAYMENTS, null, REFERENCE, withoutReference + " bank or M-Pesa payment"
                    + (withoutReference == 1 ? " has" : "s have") + " no reference. Each gets one made up from the "
                    + "import so it can still be told apart."));
        }
        if (workbook.rows(PENALTIES).isEmpty()) {
            problems.add(Problem.error(PENALTIES, null, null, "The file has no penalties to import"));
        }
        return new Result(penalties, paymentRows, problems);
    }

    /** LATE_CONTRIBUTION or FINE; FINE when blank; null when unknown. */
    static String kind(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (t) {
            case "", "fine", "fines", "penalty", "other" -> "FINE";
            case "latecontribution", "late", "latepayment", "latecontributions" -> "LATE_CONTRIBUTION";
            default -> null;
        };
    }

    private static String key(String ref) {
        return ref == null || ref.isBlank() ? null : ref.trim().toUpperCase(Locale.ROOT);
    }

    /** The reference given to a bank or M-Pesa payment the records have none for. */
    public static String madeUpReference(String batchId, int row) {
        return "IMP-" + batchId.replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT) + "-P" + row;
    }
}
