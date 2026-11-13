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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.AMOUNT;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.DATE;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.TEXT;

/**
 * Step 1 of a historical import: when each member really joined, and what the chama asked per
 * contribution period over the years. Both decide what every member owed, period by period.
 */
public final class SetupCheck {

    public static final String MEMBERS = "Members";
    public static final String SCHEDULE = "Contribution schedule";

    public static final String MEMBER = "Member no.", NAME = "Name", EMAIL = "Email", JOINED_ON = "Joined on";
    public static final String PRODUCT = "Savings product", EFFECTIVE_FROM = "Effective from",
            AMOUNT_PER_PERIOD = "Amount per period";

    private SetupCheck() {
    }

    public static SheetSpec membersSheet() {
        return SheetSpec.data(MEMBERS, true,
                Column.required(MEMBER, TEXT, 12, "Filled in for you: do not change"),
                Column.optional(NAME, TEXT, 28, "For reference only"),
                Column.optional(EMAIL, TEXT, 30, "For reference only"),
                Column.required(JOINED_ON, DATE, 14,
                        "The day the member really joined the chama; change it where the system has a later date"));
    }

    public static SheetSpec scheduleSheet(List<String> productLabels) {
        return SheetSpec.data(SCHEDULE, true,
                Column.required(PRODUCT, TEXT, 30, "The savings product, e.g. the chama's monthly deposits")
                        .choosing(productLabels),
                Column.required(EFFECTIVE_FROM, DATE, 16,
                        "The first period due on or after this day asks this amount, until the next change"),
                Column.required(AMOUNT_PER_PERIOD, AMOUNT, 20, "What each member owed per period; 0 if it stopped"));
    }

    /** A join date to move back. */
    public record JoinDate(int row, MemberRef member, LocalDate joinedOn) {
    }

    public record ScheduleRow(int row, ProductRef product, LocalDate effectiveFrom, long amountMinor) {
    }

    /**
     * @param joinDates every member's join date as it will be (also those that do not change)
     * @param schedule  oldest first
     */
    public record Plan(List<JoinDate> joinDates, List<ScheduleRow> schedule) {

        public Optional<LocalDate> historyStart() {
            return schedule.stream().map(ScheduleRow::effectiveFrom).min(LocalDate::compareTo);
        }

        public List<JoinDate> changes() {
            return joinDates.stream().filter(j -> !j.joinedOn().equals(j.member().joinedOn())).toList();
        }
    }

    public record Result(Plan plan, List<Problem> problems) {

        public boolean importable() {
            return problems.stream().noneMatch(Problem::blocking);
        }
    }

    public static Result check(Workbook workbook, HistoryChama chama) {
        List<Problem> problems = new ArrayList<>(workbook.problems());
        List<JoinDate> joinDates = members(workbook.rows(MEMBERS), chama, problems);
        List<ScheduleRow> schedule = schedule(workbook.rows(SCHEDULE), chama, problems);
        return new Result(new Plan(joinDates, schedule), problems);
    }

    private static List<JoinDate> members(List<Row> rows, HistoryChama chama, List<Problem> problems) {
        List<JoinDate> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Row r : rows) {
            Optional<MemberRef> member = chama.member(r.text(MEMBER));
            if (member.isEmpty()) {
                problems.add(Problem.error(MEMBERS, r.row(), MEMBER, "No member " + r.text(MEMBER)
                        + " in the chama. Add the member in the system, then download the template again."));
                continue;
            }
            MemberRef m = member.get();
            if (!seen.add(m.userId())) {
                problems.add(Problem.error(MEMBERS, r.row(), MEMBER, "Member " + m.number() + " appears more than once"));
                continue;
            }
            LocalDate joined = r.date(JOINED_ON);
            if (joined == null) {
                if (!r.unreadable(JOINED_ON)) {
                    problems.add(Problem.error(MEMBERS, r.row(), JOINED_ON, "The date " + m.name() + " joined is required"));
                }
                continue;
            }
            if (joined.isAfter(chama.today())) {
                problems.add(Problem.error(MEMBERS, r.row(), JOINED_ON, "Cannot be in the future"));
                continue;
            }
            if (m.joinedOn() != null && joined.isAfter(m.joinedOn())) {
                problems.add(Problem.error(MEMBERS, r.row(), JOINED_ON, "The system has " + m.name() + " joining on "
                        + m.joinedOn() + "; a join date can only be moved back, to when they really joined"));
                continue;
            }
            result.add(new JoinDate(r.row(), m, joined));
        }
        return result;
    }

    private static List<ScheduleRow> schedule(List<Row> rows, HistoryChama chama, List<Problem> problems) {
        List<ScheduleRow> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Row r : rows) {
            int before = problems.size();
            Optional<ProductRef> product = chama.savingsProduct(r.text(PRODUCT));
            if (product.isEmpty()) {
                problems.add(Problem.error(SCHEDULE, r.row(), PRODUCT, "Unknown savings product " + r.text(PRODUCT)
                        + ". Add it in the system first (Savings products), then download the template again."));
            }
            LocalDate from = r.date(EFFECTIVE_FROM);
            if (from == null && !r.unreadable(EFFECTIVE_FROM)) {
                problems.add(Problem.error(SCHEDULE, r.row(), EFFECTIVE_FROM, "The date this amount applied from is required"));
            } else if (from != null && from.isAfter(chama.today())) {
                problems.add(Problem.error(SCHEDULE, r.row(), EFFECTIVE_FROM, "Cannot be in the future"));
            }
            Long amount = r.amount(AMOUNT_PER_PERIOD);
            if (amount == null && !r.unreadable(AMOUNT_PER_PERIOD)) {
                problems.add(Problem.error(SCHEDULE, r.row(), AMOUNT_PER_PERIOD, "The amount is required (0 if it stopped)"));
            } else if (amount != null && amount < 0) {
                problems.add(Problem.error(SCHEDULE, r.row(), AMOUNT_PER_PERIOD, "Cannot be negative"));
            }
            if (problems.size() != before || from == null || amount == null) {
                continue;
            }
            if (!seen.add(product.get().id() + "/" + from)) {
                problems.add(Problem.error(SCHEDULE, r.row(), EFFECTIVE_FROM, product.get().code()
                        + " already has an amount from " + from));
                continue;
            }
            result.add(new ScheduleRow(r.row(), product.get(), from, amount));
        }
        result.sort(Comparator.comparing(ScheduleRow::effectiveFrom).thenComparing(ScheduleRow::row));

        if (rows.isEmpty() || (result.isEmpty() && problems.stream().noneMatch(p -> SCHEDULE.equals(p.sheet())))) {
            problems.add(Problem.error(SCHEDULE, null, null,
                    "Add at least one row: what each member contributed per period when the chama started"));
            return result;
        }
        ProductRef main = chama.defaultProduct();
        Optional<ScheduleRow> firstMain = result.stream().filter(s -> s.product().id().equals(main.id())).findFirst();
        if (firstMain.isEmpty()) {
            if (!result.isEmpty()) {
                problems.add(Problem.error(SCHEDULE, null, PRODUCT, main.label()
                        + " needs at least one row: it is what every member owes each period"));
            }
            return result;
        }
        if (!result.isEmpty() && result.getFirst().effectiveFrom().isBefore(firstMain.get().effectiveFrom())) {
            problems.add(Problem.error(SCHEDULE, result.getFirst().row(), EFFECTIVE_FROM, "The schedule starts with "
                    + result.getFirst().product().code() + " on " + result.getFirst().effectiveFrom() + ", before "
                    + main.code() + " (" + firstMain.get().effectiveFrom() + "). The chama's history starts with its first "
                    + main.code() + " amount."));
        }
        if (firstMain.get().amountMinor() == 0) {
            problems.add(Problem.error(SCHEDULE, firstMain.get().row(), AMOUNT_PER_PERIOD, main.code()
                    + " must ask more than 0 when the chama starts"));
        }
        result.stream().filter(s -> s.product().id().equals(main.id()) && s.amountMinor() == 0)
                .forEach(s -> problems.add(Problem.error(SCHEDULE, s.row(), AMOUNT_PER_PERIOD, main.code()
                        + " is what every member owes each period, so it cannot drop to 0")));
        result.stream().filter(s -> !s.product().active())
                .forEach(s -> problems.add(Problem.warning(SCHEDULE, s.row(), PRODUCT, s.product().label()
                        + " is no longer offered; members still owe what it asked in those years")));
        return result;
    }
}
