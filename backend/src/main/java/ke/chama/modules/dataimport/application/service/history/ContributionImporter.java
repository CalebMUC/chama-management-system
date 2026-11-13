package ke.chama.modules.dataimport.application.service.history;

import ke.chama.modules.contribution.api.ContributionHistory;
import ke.chama.modules.contribution.api.ContributionHistory.HistoricalPayment;
import ke.chama.modules.contribution.api.ContributionHistory.Standing;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.SummaryLine;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.TableView;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords.Batch;
import ke.chama.modules.dataimport.domain.model.history.ContributionCheck;
import ke.chama.modules.dataimport.domain.model.history.ContributionCheck.Payment;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.MemberRef;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.ProductRef;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Template;
import ke.chama.modules.dataimport.domain.model.history.Sheets.TemplateSheet;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Step 2: every contribution payment. Each is split across the member's savings products as the
 * system splits a payment recorded by hand, and posted to the ledger on the day it was paid.
 */
@Component
class ContributionImporter implements HistoryImporter {

    private final ContributionHistory contributions;

    ContributionImporter(ContributionHistory contributions) {
        this.contributions = contributions;
    }

    @Override
    public Kind kind() {
        return Kind.CONTRIBUTIONS;
    }

    @Override
    public List<SheetSpec> sheets(UUID chamaId, HistoryChama chama) {
        return List.of(sheet(chama));
    }

    private static SheetSpec sheet(HistoryChama chama) {
        return ContributionCheck.sheet(chama.memberLabels(),
                chama.savingsProducts().stream().filter(ProductRef::active).map(ProductRef::label).toList());
    }

    @Override
    public Template template(UUID chamaId, HistoryChama chama) {
        return new Template(HistoryTemplates.readMe(
                "Step 2 of 7 - Contributions: every payment members made",
                "One row per payment, as the money came in: who paid, when, how much and how (Cash, Bank or M-Pesa). "
                        + "Put every payment since the chama started; you can split them over several files (e.g. one per "
                        + "year) and import each in turn.",
                "Leave 'Savings product' blank and the system splits each payment as it would today: the member's oldest "
                        + "dues first, across their products, and anything extra as savings in advance. Name a product only "
                        + "when the whole payment went to it (e.g. a share capital top-up).",
                "The preview shows each member's total and where they will stand: in arrears or paid in advance."),
                List.of(new TemplateSheet(sheet(chama), List.of()), HistoryTemplates.membersLookup(chama)));
    }

    @Override
    public Checked check(UUID chamaId, Workbook workbook, HistoryChama chama) {
        ContributionCheck.Result result = ContributionCheck.check(workbook,
                chama.withReferences(contributions.usedReferences(chamaId)));
        List<Payment> payments = result.payments();
        long total = payments.stream().mapToLong(Payment::amountMinor).sum();

        List<SummaryLine> summary = new ArrayList<>();
        summary.add(SummaryLine.count("Payments", payments.size()));
        summary.add(SummaryLine.amount("Total", total, chama.format(total)));
        summary.add(SummaryLine.count("Members", payments.stream().map(p -> p.member().userId()).distinct().count()));
        payments.stream().map(Payment::paidOn).min(LocalDate::compareTo)
                .ifPresent(d -> summary.add(SummaryLine.date("First payment", d)));
        payments.stream().map(Payment::paidOn).max(LocalDate::compareTo)
                .ifPresent(d -> summary.add(SummaryLine.date("Last payment", d)));
        for (String method : List.of("CASH", "BANK", "MPESA")) {
            long byMethod = payments.stream().filter(p -> p.method().equals(method)).mapToLong(Payment::amountMinor).sum();
            if (byMethod > 0) {
                summary.add(SummaryLine.amount("By " + HistoryChama.methodLabel(method), byMethod, chama.format(byMethod)));
            }
        }
        return new Checked(payments, workbook.rows(ContributionCheck.CONTRIBUTIONS).size(), result.problems(), summary,
                List.of(memberTable(chamaId, payments, chama)));
    }

    /** Each member's payments in the file, and where they will stand once imported. */
    private TableView memberTable(UUID chamaId, List<Payment> payments, HistoryChama chama) {
        Map<UUID, Standing> standings = contributions.standings(chamaId);
        Map<UUID, long[]> inFile = new LinkedHashMap<>();
        for (Payment p : payments) {
            long[] t = inFile.computeIfAbsent(p.member().userId(), k -> new long[2]);
            t[0]++;
            t[1] += p.amountMinor();
        }
        List<List<String>> rows = new ArrayList<>();
        for (MemberRef m : chama.members().stream().sorted(Comparator.comparingInt(MemberRef::number)).toList()) {
            long[] t = inFile.getOrDefault(m.userId(), new long[2]);
            Standing s = standings.getOrDefault(m.userId(), new Standing(0, 0));
            long balance = s.paidMinor() + t[1] - s.dueToDateMinor();
            String standing = balance < 0 ? "Arrears " + chama.format(-balance)
                    : balance > 0 ? "Advance " + chama.format(balance) : "Up to date";
            rows.add(List.of(m.label(), String.valueOf(t[0]), chama.format(t[1]), chama.format(s.paidMinor()),
                    chama.format(s.dueToDateMinor()), standing));
        }
        return new TableView("Members after this import",
                List.of("Member", "Payments", "In this file", "Already recorded", "Due to date", "Standing"), rows);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Applied apply(UUID chamaId, UUID actorUserId, UUID batchId, Checked checked, HistoryChama chama) {
        List<Payment> payments = (List<Payment>) checked.plan();
        List<HistoricalPayment> history = payments.stream().map(p -> new HistoricalPayment(p.member().userId(),
                p.amountMinor(), p.method(),
                p.reference() == null && !p.method().equals("CASH")
                        ? ContributionCheck.madeUpReference(batchId.toString(), p.row()) : p.reference(),
                p.paidOn(), p.notes(), Optional.ofNullable(p.product()).map(ProductRef::id).orElse(null))).toList();
        int recorded = contributions.importPayments(chamaId, actorUserId, batchId, history);
        long total = payments.stream().mapToLong(Payment::amountMinor).sum();
        List<SummaryLine> summary = new ArrayList<>();
        summary.add(SummaryLine.count("Payments", recorded));
        summary.add(SummaryLine.amount("Total", total, chama.format(total)));
        payments.stream().map(Payment::paidOn).min(LocalDate::compareTo)
                .ifPresent(d -> summary.add(SummaryLine.date("First payment", d)));
        payments.stream().map(Payment::paidOn).max(LocalDate::compareTo)
                .ifPresent(d -> summary.add(SummaryLine.date("Last payment", d)));
        return new Applied(checked.rows(), summary, Map.of());
    }

    @Override
    public Optional<String> undoBlocked(UUID chamaId, Batch batch) {
        return Optional.empty();
    }

    @Override
    public void undo(UUID chamaId, UUID actorUserId, Batch batch) {
        contributions.undoPayments(chamaId, actorUserId, batch.id());
    }
}
