package ke.chama.modules.dataimport.application.service.history;

import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.SummaryLine;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.TableView;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords.Batch;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama;
import ke.chama.modules.dataimport.domain.model.history.InvestmentCheck;
import ke.chama.modules.dataimport.domain.model.history.InvestmentCheck.Movement;
import ke.chama.modules.dataimport.domain.model.history.InvestmentCheck.Outcome;
import ke.chama.modules.dataimport.domain.model.history.InvestmentCheck.PlannedInvestment;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Template;
import ke.chama.modules.dataimport.domain.model.history.Sheets.TemplateSheet;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;
import ke.chama.modules.investment.api.InvestmentHistory;
import ke.chama.modules.investment.api.InvestmentHistory.HistoricalInvestment;
import ke.chama.modules.investment.api.InvestmentHistory.HistoricalMovement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Step 5: investments, each funded on its own day and replayed through everything that happened to it. */
@Component
class InvestmentImporter implements HistoryImporter {

    private final InvestmentHistory investments;

    InvestmentImporter(InvestmentHistory investments) {
        this.investments = investments;
    }

    @Override
    public Kind kind() {
        return Kind.INVESTMENTS;
    }

    @Override
    public List<SheetSpec> sheets(UUID chamaId, HistoryChama chama) {
        return InvestmentCheck.sheets(investments.typeNames().stream().sorted().map(InvestmentCheck::typeLabel).toList());
    }

    @Override
    public Template template(UUID chamaId, HistoryChama chama) {
        List<SheetSpec> sheets = sheets(chamaId, chama);
        return new Template(HistoryTemplates.readMe(
                "Step 5 of 7 - Investments",
                "INVESTMENTS: one row per investment the chama made, still held or not, with your own short ref "
                        + "(I001, I002, ...) and what first went in.",
                "INVESTMENT ACTIVITY: everything that happened to each one since, one row each: Deposit (a top-up), "
                        + "Income (interest, dividends, rent; Reinvested if it stayed in, e.g. money market interest), "
                        + "Withdrawal (part taken out), Valuation (what it was worth on a day), and Closure (sold, "
                        + "redeemed or matured: the amount received). Anything after a closure is refused.",
                "An investment still held carries on in the system: record its income and valuations there from now on."),
                List.of(new TemplateSheet(sheets.get(0), List.of()), new TemplateSheet(sheets.get(1), List.of())));
    }

    @Override
    public Checked check(UUID chamaId, Workbook workbook, HistoryChama chama) {
        InvestmentCheck.Result result = InvestmentCheck.check(workbook,
                chama.withReferences(investments.usedReferences(chamaId)), investments.typeNames());
        List<PlannedInvestment> planned = result.investments();
        long putIn = 0;
        long income = 0;
        long held = 0;
        int active = 0;
        List<List<String>> rows = new ArrayList<>();
        for (PlannedInvestment i : planned) {
            Outcome o = i.outcome();
            putIn += o.putInMinor();
            income += o.incomeMinor();
            if (!o.closed()) {
                active++;
                held += o.bookValueMinor();
            }
            rows.add(List.of(i.ref(), i.name(), InvestmentCheck.typeLabel(i.type()), i.fundedOn().toString(),
                    chama.format(o.putInMinor()), chama.format(o.incomeMinor()), chama.format(o.takenOutMinor()),
                    o.closed() ? "" : chama.format(o.bookValueMinor()),
                    o.latestValueMinor() == null || o.closed() ? "" : chama.format(o.latestValueMinor()),
                    o.closed() ? "Closed (" + (o.realisedGainMinor() >= 0 ? "gain " : "loss ")
                            + chama.format(Math.abs(o.realisedGainMinor())) + ")" : "Held"));
        }
        List<SummaryLine> summary = List.of(
                SummaryLine.count("Investments", planned.size()),
                SummaryLine.count("Still held", active),
                SummaryLine.amount("Put in", putIn, chama.format(putIn)),
                SummaryLine.amount("Income", income, chama.format(income)),
                SummaryLine.amount("Held at cost", held, chama.format(held)));
        return new Checked(planned, workbook.rows(InvestmentCheck.INVESTMENTS).size() + result.movementRows(),
                result.problems(), summary, List.of(new TableView("Investments after this import",
                List.of("Ref", "Name", "Type", "Invested on", "Put in", "Income", "Taken out", "Book value",
                        "Latest value", "Status"), rows)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Applied apply(UUID chamaId, UUID actorUserId, UUID batchId, Checked checked, HistoryChama chama) {
        List<PlannedInvestment> planned = (List<PlannedInvestment>) checked.plan();
        String batch = batchId.toString();
        List<HistoricalInvestment> history = new ArrayList<>();
        for (PlannedInvestment i : planned) {
            List<HistoricalMovement> movements = new ArrayList<>();
            for (Movement m : i.movements()) {
                String reference = m.reference() == null && m.method() != null
                        && (m.method().equals("BANK") || m.method().equals("MPESA")) && m.amountMinor() > 0
                        ? InvestmentCheck.madeUpReference(batch, InvestmentCheck.MOVEMENTS, m.row()) : m.reference();
                movements.add(new HistoricalMovement(m.kind(), m.date(), m.amountMinor(),
                        m.method() == null ? "CASH" : m.method(), reference, m.note()));
            }
            String reference = i.reference() == null && !i.method().equals("CASH")
                    ? InvestmentCheck.madeUpReference(batch, InvestmentCheck.INVESTMENTS, i.row()) : i.reference();
            history.add(new HistoricalInvestment(i.name(), i.type(), i.institution(), i.description(), i.amountMinor(),
                    i.fundedOn(), i.method(), reference, i.expectedReturnBasisPoints(), i.maturityDate(), movements));
        }
        int count = investments.importInvestments(chamaId, actorUserId, batchId, history);
        long putIn = planned.stream().mapToLong(i -> i.outcome().putInMinor()).sum();
        return new Applied(checked.rows(), List.of(
                SummaryLine.count("Investments", count),
                SummaryLine.count("Still held", planned.stream().filter(i -> !i.outcome().closed()).count()),
                SummaryLine.amount("Put in", putIn, chama.format(putIn))), Map.of());
    }

    @Override
    public Optional<String> undoBlocked(UUID chamaId, Batch batch) {
        return investments.undoBlocked(chamaId, batch.id());
    }

    @Override
    public void undo(UUID chamaId, UUID actorUserId, Batch batch) {
        investments.undoInvestments(chamaId, actorUserId, batch.id());
    }
}
