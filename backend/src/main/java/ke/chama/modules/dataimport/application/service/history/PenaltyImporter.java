package ke.chama.modules.dataimport.application.service.history;

import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.SummaryLine;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.TableView;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords.Batch;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.MemberRef;
import ke.chama.modules.dataimport.domain.model.history.PenaltyCheck;
import ke.chama.modules.dataimport.domain.model.history.PenaltyCheck.PlannedPenalty;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Template;
import ke.chama.modules.dataimport.domain.model.history.Sheets.TemplateSheet;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;
import ke.chama.modules.penalty.api.PenaltyHistory;
import ke.chama.modules.penalty.api.PenaltyHistory.HistoricalPayment;
import ke.chama.modules.penalty.api.PenaltyHistory.HistoricalPenalty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Step 4: penalties and fines as the chama recorded them, with their payments and waivers. */
@Component
class PenaltyImporter implements HistoryImporter {

    private final PenaltyHistory penalties;

    PenaltyImporter(PenaltyHistory penalties) {
        this.penalties = penalties;
    }

    @Override
    public Kind kind() {
        return Kind.PENALTIES;
    }

    @Override
    public List<SheetSpec> sheets(UUID chamaId, HistoryChama chama) {
        return PenaltyCheck.sheets(chama.memberLabels());
    }

    @Override
    public Template template(UUID chamaId, HistoryChama chama) {
        List<SheetSpec> sheets = sheets(chamaId, chama);
        return new Template(HistoryTemplates.readMe(
                "Step 4 of 7 - Penalties and fines",
                "PENALTIES: one row per penalty or fine the chama charged, as you recorded it, with your own short ref "
                        + "(P001, P002, ...). The system never works out penalties for the years before it was used, "
                        + "so only what is in this file is charged for them.",
                "If what was left unpaid was waived, put the day in 'Waived on' (and why, in 'Waiver reason').",
                "PENALTY PAYMENTS: one row per payment, with the ref of the penalty it pays. Leave out anything "
                        + "still unpaid: it stays owed, and can be paid or waived in the system."),
                List.of(new TemplateSheet(sheets.get(0), List.of()), new TemplateSheet(sheets.get(1), List.of()),
                        HistoryTemplates.membersLookup(chama)));
    }

    @Override
    public Checked check(UUID chamaId, Workbook workbook, HistoryChama chama) {
        PenaltyCheck.Result result = PenaltyCheck.check(workbook, chama.withReferences(penalties.usedReferences(chamaId)));
        List<PlannedPenalty> planned = result.penalties();
        long charged = planned.stream().mapToLong(PlannedPenalty::amountMinor).sum();
        long paid = planned.stream().mapToLong(PlannedPenalty::paidMinor).sum();
        long waived = planned.stream().mapToLong(PlannedPenalty::waivedMinor).sum();
        long owed = planned.stream().mapToLong(PlannedPenalty::outstandingMinor).sum();
        List<SummaryLine> summary = List.of(
                SummaryLine.count("Penalties", planned.size()),
                SummaryLine.count("Payments", planned.stream().mapToLong(p -> p.payments().size()).sum()),
                SummaryLine.amount("Charged", charged, chama.format(charged)),
                SummaryLine.amount("Paid", paid, chama.format(paid)),
                SummaryLine.amount("Waived", waived, chama.format(waived)),
                SummaryLine.amount("Still owed", owed, chama.format(owed)));

        Map<UUID, long[]> byMember = new LinkedHashMap<>();
        for (PlannedPenalty p : planned) {
            long[] t = byMember.computeIfAbsent(p.member().userId(), k -> new long[5]);
            t[0]++;
            t[1] += p.amountMinor();
            t[2] += p.paidMinor();
            t[3] += p.waivedMinor();
            t[4] += p.outstandingMinor();
        }
        List<List<String>> rows = new ArrayList<>();
        for (MemberRef m : chama.members().stream().sorted(Comparator.comparingInt(MemberRef::number)).toList()) {
            long[] t = byMember.get(m.userId());
            if (t != null) {
                rows.add(List.of(m.label(), String.valueOf(t[0]), chama.format(t[1]), chama.format(t[2]),
                        chama.format(t[3]), chama.format(t[4])));
            }
        }
        return new Checked(planned, workbook.rows(PenaltyCheck.PENALTIES).size() + result.paymentRows(),
                result.problems(), summary, List.of(new TableView("Penalties per member",
                List.of("Member", "Penalties", "Charged", "Paid", "Waived", "Still owed"), rows)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Applied apply(UUID chamaId, UUID actorUserId, UUID batchId, Checked checked, HistoryChama chama) {
        List<PlannedPenalty> planned = (List<PlannedPenalty>) checked.plan();
        String batch = batchId.toString();
        List<HistoricalPenalty> history = planned.stream().map(p -> new HistoricalPenalty(p.member().userId(), p.kind(),
                p.ref(), p.reason(), p.amountMinor(), p.chargedOn(), p.payments().stream()
                .map(pay -> new HistoricalPayment(pay.paidOn(), pay.amountMinor(), pay.method(),
                        pay.reference() == null && !pay.method().equals("CASH")
                                ? PenaltyCheck.madeUpReference(batch, pay.row()) : pay.reference()))
                .toList(), p.waivedOn(), p.waiverReason())).toList();
        int count = penalties.importPenalties(chamaId, actorUserId, batchId, history);
        long charged = planned.stream().mapToLong(PlannedPenalty::amountMinor).sum();
        long owed = planned.stream().mapToLong(PlannedPenalty::outstandingMinor).sum();
        return new Applied(checked.rows(), List.of(
                SummaryLine.count("Penalties", count),
                SummaryLine.amount("Charged", charged, chama.format(charged)),
                SummaryLine.amount("Still owed", owed, chama.format(owed))), Map.of());
    }

    @Override
    public Optional<String> undoBlocked(UUID chamaId, Batch batch) {
        return penalties.undoBlocked(chamaId, batch.id());
    }

    @Override
    public void undo(UUID chamaId, UUID actorUserId, Batch batch) {
        penalties.undoPenalties(chamaId, actorUserId, batch.id());
    }
}
