package ke.chama.modules.dataimport.application.service.history;

import ke.chama.modules.account.api.MemberAccountSummary;
import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.api.SavingsProductSummary;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ContributionRules;
import ke.chama.modules.contribution.api.ContributionHistory;
import ke.chama.modules.contribution.api.ContributionHistory.PlannedPeriod;
import ke.chama.modules.contribution.api.ContributionHistory.ScheduledAmount;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.SummaryLine;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.TableView;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords.Batch;
import ke.chama.modules.dataimport.domain.model.Problem;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.MemberRef;
import ke.chama.modules.dataimport.domain.model.history.SetupCheck;
import ke.chama.modules.dataimport.domain.model.history.SetupCheck.JoinDate;
import ke.chama.modules.dataimport.domain.model.history.SetupCheck.Plan;
import ke.chama.modules.dataimport.domain.model.history.SetupCheck.ScheduleRow;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Template;
import ke.chama.modules.dataimport.domain.model.history.Sheets.TemplateSheet;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;
import ke.chama.modules.member.api.MemberImport;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Step 1: members' real join dates and the contribution schedule over the years. Applying it moves
 * join dates (and the dates members' accounts opened) back, then back-fills the contribution periods
 * before the chama used the system, so every member owes exactly what they owed then.
 */
@Component
class SetupImporter implements HistoryImporter {

    static final String JOIN_DATES = "joinDates";
    static final String ACCOUNTS = "accountOpenedOn";

    private final ContributionHistory contributions;
    private final MemberImport memberImport;
    private final MemberAccounts memberAccounts;
    private final SavingsProducts savingsProducts;
    private final ChamaDirectory chamas;

    SetupImporter(ContributionHistory contributions, MemberImport memberImport, MemberAccounts memberAccounts,
                  SavingsProducts savingsProducts, ChamaDirectory chamas) {
        this.chamas = chamas;
        this.contributions = contributions;
        this.memberImport = memberImport;
        this.memberAccounts = memberAccounts;
        this.savingsProducts = savingsProducts;
    }

    @Override
    public Kind kind() {
        return Kind.SETUP;
    }

    @Override
    public List<SheetSpec> sheets(UUID chamaId, HistoryChama chama) {
        return List.of(SetupCheck.membersSheet(), SetupCheck.scheduleSheet(productLabels(chama)));
    }

    private static List<String> productLabels(HistoryChama chama) {
        return chama.savingsProducts().stream().map(HistoryChama.ProductRef::label).toList();
    }

    @Override
    public Template template(UUID chamaId, HistoryChama chama) {
        List<List<Object>> members = new ArrayList<>();
        for (MemberRef m : chama.members()) {
            members.add(new ArrayList<>(java.util.Arrays.asList(String.valueOf(m.number()), m.name(), m.email(),
                    m.joinedOn())));
        }
        int digits = Math.max(Currency.getInstance(chama.currency()).getDefaultFractionDigits(), 0);
        List<List<Object>> schedule = new ArrayList<>();
        for (SavingsProductSummary p : savingsProducts.products(chamaId)) {
            if (p.active() && (p.defaultProduct() || p.contributionMinor() > 0)) {
                long amount = p.defaultProduct() ? defaultAmount(chamaId, p) : p.contributionMinor();
                schedule.add(new ArrayList<>(java.util.Arrays.asList(p.code() + " - " + p.name(), null,
                        BigDecimal.valueOf(amount, digits))));
            }
        }
        return new Template(HistoryTemplates.readMe(
                "Step 1 of 7 - Setup: when members joined, and what each member owed per period",
                "Do this first: every other import depends on it. It decides what each member owed, period by period, "
                        + "since the chama started, so their arrears and advances come out right.",
                "MEMBERS: every member in the system, filled in for you. In 'Joined on', put the day each member really "
                        + "joined the chama (members added by hand show the day they were added). Dates can only move back.",
                "CONTRIBUTION SCHEDULE: what each member owed per contribution period, and from when. Put a row with the "
                        + "date the chama started and the amount then. Each time the amount changed, add a row with the "
                        + "new amount and the date it applied from. Do the same for every savings product the chama had "
                        + "(e.g. welfare or share capital); a product that stopped gets a row with 0.",
                "The system creates the chama's contribution periods from that first date up to its first period in the "
                        + "system. Those years are never penalised automatically: import the penalties as you recorded them "
                        + "(step 4)."),
                List.of(new TemplateSheet(SetupCheck.membersSheet(), members),
                        new TemplateSheet(SetupCheck.scheduleSheet(productLabels(chama)), schedule)));
    }

    /** The default product asks the chama's contribution amount (kept on the chama's settings). */
    private long defaultAmount(UUID chamaId, SavingsProductSummary p) {
        return chamas.contributionRules(chamaId).map(ContributionRules::amountMinor).orElse(p.contributionMinor());
    }

    @Override
    public Checked check(UUID chamaId, Workbook workbook, HistoryChama chama) {
        SetupCheck.Result result = SetupCheck.check(workbook, chama);
        List<Problem> problems = new ArrayList<>(result.problems());
        Plan plan = result.plan();
        List<PlannedPeriod> periods = result.importable() ? contributions.plannedPeriods(chamaId, schedule(plan)) : List.of();
        if (result.importable() && periods.isEmpty()) {
            problems.add(Problem.warning(SetupCheck.SCHEDULE, null, null, "No contribution periods will be created: the "
                    + "schedule starts on or after the chama's first period in the system. Check the earliest date."));
        }
        if (result.importable() && periods.isEmpty() && plan.changes().isEmpty()) {
            problems.add(Problem.error(null, null, null, "The file changes nothing"));
        }
        int rows = workbook.rows(SetupCheck.MEMBERS).size() + workbook.rows(SetupCheck.SCHEDULE).size();

        List<SummaryLine> summary = new ArrayList<>();
        summary.add(SummaryLine.count("Members", plan.joinDates().size()));
        summary.add(SummaryLine.count("Join dates moved back", plan.changes().size()));
        plan.historyStart().ifPresent(d -> summary.add(SummaryLine.date("History starts", d)));
        summary.add(SummaryLine.count("Contribution periods to create", periods.size()));
        if (!periods.isEmpty()) {
            summary.add(SummaryLine.text("Periods", periods.getFirst().key() + " to " + periods.getLast().key()));
        }

        List<TableView> tables = new ArrayList<>();
        tables.add(new TableView("Join dates moved back", List.of("Member", "In the system", "Will be"),
                plan.changes().stream().map(j -> List.of(j.member().label(),
                        j.member().joinedOn() == null ? "" : j.member().joinedOn().toString(), j.joinedOn().toString()))
                        .toList()));
        tables.add(new TableView("Contribution schedule", List.of("Savings product", "From", "Per period"),
                plan.schedule().stream().map(s -> List.of(s.product().label(), s.effectiveFrom().toString(),
                        chama.format(s.amountMinor()))).toList()));
        tables.add(new TableView("Periods to create", List.of("From", "To", "Periods", "Each member owes per period"),
                periodRuns(periods, chama)));
        return new Checked(plan, rows, problems, summary, tables);
    }

    /** Consecutive periods asking the same amount, as one line each. */
    private static List<List<String>> periodRuns(List<PlannedPeriod> periods, HistoryChama chama) {
        List<List<String>> runs = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= periods.size(); i++) {
            if (i == periods.size() || periods.get(i).amountDueMinor() != periods.get(start).amountDueMinor()) {
                if (!periods.isEmpty()) {
                    runs.add(List.of(periods.get(start).key(), periods.get(i - 1).key(), String.valueOf(i - start),
                            chama.format(periods.get(start).amountDueMinor())));
                }
                start = i;
            }
        }
        return runs;
    }

    private static List<ScheduledAmount> schedule(Plan plan) {
        return plan.schedule().stream()
                .map(s -> new ScheduledAmount(s.product().id(), s.effectiveFrom(), s.amountMinor())).toList();
    }

    @Override
    public Applied apply(UUID chamaId, UUID actorUserId, UUID batchId, Checked checked, HistoryChama chama) {
        Plan plan = (Plan) checked.plan();
        // Accounts in mandatory products open lazily. Open any missing ones now, on today's join
        // dates, so moving them back below is recorded and undone like every other account.
        memberAccounts.openMandatoryAccounts(chamaId);
        Map<String, String> joinUndo = new LinkedHashMap<>();
        Map<UUID, LocalDate> joined = new HashMap<>();
        chama.members().forEach(m -> joined.put(m.userId(), m.joinedOn()));
        for (JoinDate j : plan.changes()) {
            LocalDate before = memberImport.correctJoinDate(chamaId, j.member().userId(), j.joinedOn(), actorUserId);
            joinUndo.put(j.member().userId().toString(), before == null ? "" : before.toString());
            joined.put(j.member().userId(), j.joinedOn());
        }

        // Accounts open from when the member joined; other products from when the schedule starts
        // asking for them, if later. Dates only move back.
        Map<UUID, LocalDate> productStarts = new HashMap<>();
        for (ScheduleRow s : plan.schedule()) {
            if (s.amountMinor() > 0) {
                productStarts.merge(s.product().id(), s.effectiveFrom(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        UUID defaultProduct = chama.defaultProduct().id();
        Map<String, String> accountUndo = new LinkedHashMap<>();
        for (MemberAccountSummary account : memberAccounts.accounts(chamaId)) {
            LocalDate memberJoined = joined.get(account.memberUserId());
            if (!account.active() || memberJoined == null) {
                continue;
            }
            Optional<LocalDate> target = account.productId().equals(defaultProduct)
                    ? Optional.of(memberJoined)
                    : Optional.ofNullable(productStarts.get(account.productId()))
                    .map(start -> start.isAfter(memberJoined) ? start : memberJoined);
            if (target.isPresent() && target.get().isBefore(account.openedOn())) {
                LocalDate before = memberAccounts.correctOpenedOn(chamaId, account.id(), target.get(), actorUserId);
                accountUndo.put(account.id().toString(), before.toString());
            }
        }

        int periods = contributions.backfill(chamaId, schedule(plan), batchId);
        List<SummaryLine> summary = new ArrayList<>();
        summary.add(SummaryLine.count("Join dates moved back", joinUndo.size()));
        summary.add(SummaryLine.count("Accounts back-dated", accountUndo.size()));
        summary.add(SummaryLine.count("Contribution periods created", periods));
        plan.historyStart().ifPresent(d -> summary.add(SummaryLine.date("History starts", d)));
        return new Applied(checked.rows(), summary, Map.of(JOIN_DATES, joinUndo, ACCOUNTS, accountUndo));
    }

    @Override
    public Optional<String> undoBlocked(UUID chamaId, Batch batch) {
        return Optional.empty();
    }

    @Override
    public void undo(UUID chamaId, UUID actorUserId, Batch batch) {
        batch.undoData().getOrDefault(JOIN_DATES, Map.of()).forEach((user, before) -> {
            if (!before.isEmpty()) {
                memberImport.correctJoinDate(chamaId, UUID.fromString(user), LocalDate.parse(before), actorUserId);
            }
        });
        List<UUID> accounts = new ArrayList<>();
        batch.undoData().getOrDefault(ACCOUNTS, Map.of()).forEach((account, before) -> {
            memberAccounts.correctOpenedOn(chamaId, UUID.fromString(account), LocalDate.parse(before), actorUserId);
            accounts.add(UUID.fromString(account));
        });
        contributions.removeBackfill(chamaId, batch.id(), accounts);
    }
}
