package ke.chama.modules.dataimport.application.service.history;

import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.SummaryLine;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.TableView;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords.Batch;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama;
import ke.chama.modules.dataimport.domain.model.history.LoanCheck;
import ke.chama.modules.dataimport.domain.model.history.LoanCheck.LoanProductRef;
import ke.chama.modules.dataimport.domain.model.history.LoanCheck.PlannedLoan;
import ke.chama.modules.dataimport.domain.model.history.LoanCheck.Repayment;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Template;
import ke.chama.modules.dataimport.domain.model.history.Sheets.TemplateSheet;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;
import ke.chama.modules.loan.api.LoanHistory;
import ke.chama.modules.loan.api.LoanHistory.HistoricalLoan;
import ke.chama.modules.loan.api.LoanHistory.HistoricalRepayment;
import ke.chama.modules.loan.api.LoanHistory.Projection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Step 3: loans and their repayments. Loans still being repaid carry on in the system with their
 * schedule, so the preview shows what each borrower still owes, what is overdue and what is due next.
 */
@Component
class LoanImporter implements HistoryImporter {

    private final LoanHistory loans;

    LoanImporter(LoanHistory loans) {
        this.loans = loans;
    }

    @Override
    public Kind kind() {
        return Kind.LOANS;
    }

    private List<LoanProductRef> products(UUID chamaId) {
        return loans.products(chamaId).stream().map(p -> new LoanProductRef(p.id(), p.code(), p.name(),
                p.interestBasisPoints(), p.maxTermMonths(), p.defaultProduct(), p.active())).toList();
    }

    @Override
    public List<SheetSpec> sheets(UUID chamaId, HistoryChama chama) {
        return LoanCheck.sheets(chama.memberLabels(), products(chamaId));
    }

    @Override
    public Template template(UUID chamaId, HistoryChama chama) {
        List<SheetSpec> sheets = sheets(chamaId, chama);
        LoanProductRef main = products(chamaId).stream().filter(LoanProductRef::defaultProduct).findFirst().orElseThrow();
        return new Template(HistoryTemplates.readMe(
                "Step 3 of 7 - Loans and repayments",
                "LOANS: one row per loan since the chama started, fully repaid or not. Give each a short ref of your own "
                        + "(L001, L002, ...). Interest is flat: a share of the principal for the whole term. Leave Interest "
                        + "blank to use " + main.rate() + " (" + main.name() + "); type the amount if a loan was different.",
                "REPAYMENTS: one row per repayment, with the ref of the loan it repays.",
                "A loan still being repaid carries on in the system: its schedule is equal monthly instalments of "
                        + "principal plus interest, the first a month after it was paid out. The preview shows, per loan, "
                        + "what is still owed, what is overdue and the next instalment; after importing, download the "
                        + "loan schedules from the Loans page. A member repays one loan per product at a time."),
                List.of(new TemplateSheet(sheets.get(0), List.of()), new TemplateSheet(sheets.get(1), List.of()),
                        HistoryTemplates.membersLookup(chama)));
    }

    @Override
    public Checked check(UUID chamaId, Workbook workbook, HistoryChama chama) {
        LoanCheck.Result result = LoanCheck.check(workbook, chama.withReferences(loans.usedReferences(chamaId)),
                products(chamaId), loans.openLoans(chamaId));
        List<PlannedLoan> planned = result.loans();
        boolean clean = result.problems().stream().noneMatch(p -> p.blocking());

        List<List<String>> rows = new ArrayList<>();
        long outstanding = 0;
        long overdue = 0;
        int active = 0;
        for (PlannedLoan loan : planned) {
            if (!clean) {
                break;
            }
            Projection p = loans.project(chamaId, historical(loan, null));
            outstanding += p.outstandingMinor();
            overdue += p.arrearsMinor();
            if ("ACTIVE".equals(p.status())) {
                active++;
            }
            rows.add(List.of(loan.ref(), loan.member().label(), loan.disbursedOn().toString(),
                    chama.format(loan.principalMinor()), chama.format(loan.interestMinor()), chama.format(p.totalDueMinor()),
                    chama.format(p.repaidMinor()), chama.format(p.outstandingMinor()),
                    p.arrearsMinor() == 0 ? "" : chama.format(p.arrearsMinor()) + " (" + p.instalmentsOverdue() + ")",
                    p.nextDueDate() == null ? "" : chama.format(p.nextDueMinor()) + " on " + p.nextDueDate(),
                    "ACTIVE".equals(p.status()) ? "Being repaid" : "Repaid"));
        }
        long principal = planned.stream().mapToLong(PlannedLoan::principalMinor).sum();
        long interest = planned.stream().mapToLong(PlannedLoan::interestMinor).sum();
        long repaid = planned.stream().mapToLong(PlannedLoan::repaidMinor).sum();
        List<SummaryLine> summary = new ArrayList<>();
        summary.add(SummaryLine.count("Loans", planned.size()));
        summary.add(SummaryLine.count("Repayments", planned.stream().mapToLong(l -> l.repayments().size()).sum()));
        summary.add(SummaryLine.amount("Lent", principal, chama.format(principal)));
        summary.add(SummaryLine.amount("Interest", interest, chama.format(interest)));
        summary.add(SummaryLine.amount("Repaid", repaid, chama.format(repaid)));
        if (clean) {
            summary.add(SummaryLine.count("Still being repaid", active));
            summary.add(SummaryLine.amount("Outstanding", outstanding, chama.format(outstanding)));
            summary.add(SummaryLine.amount("Overdue", overdue, chama.format(overdue)));
        }
        int rowCount = workbook.rows(LoanCheck.LOANS).size() + result.repaymentRows();
        return new Checked(planned, rowCount, result.problems(), summary, List.of(new TableView("Loans after this import",
                List.of("Ref", "Member", "Paid out", "Principal", "Interest", "Total", "Repaid", "Outstanding",
                        "Overdue (instalments)", "Next instalment", "Status"), rows)));
    }

    private static HistoricalLoan historical(PlannedLoan loan, String batchId) {
        List<HistoricalRepayment> repayments = new ArrayList<>();
        for (Repayment r : loan.repayments()) {
            repayments.add(new HistoricalRepayment(r.paidOn(), r.amountMinor(), r.method(),
                    reference(r.reference(), r.method(), batchId, LoanCheck.REPAYMENTS, r.row())));
        }
        return new HistoricalLoan(loan.member().userId(), loan.product().id(), loan.principalMinor(),
                loan.interestMinor(), loan.termMonths(), loan.disbursedOn(), loan.purpose(), loan.method(),
                reference(loan.reference(), loan.method(), batchId, LoanCheck.LOANS, loan.row()), repayments);
    }

    private static String reference(String reference, String method, String batchId, String sheet, int row) {
        if (reference != null || method.equals("CASH") || batchId == null) {
            return reference;
        }
        return LoanCheck.madeUpReference(batchId, sheet, row);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Applied apply(UUID chamaId, UUID actorUserId, UUID batchId, Checked checked, HistoryChama chama) {
        List<PlannedLoan> planned = (List<PlannedLoan>) checked.plan();
        List<UUID> ids = loans.importLoans(chamaId, actorUserId, batchId,
                planned.stream().map(l -> historical(l, batchId.toString())).toList());
        long principal = planned.stream().mapToLong(PlannedLoan::principalMinor).sum();
        long outstanding = planned.stream().mapToLong(PlannedLoan::outstandingMinor).sum();
        return new Applied(checked.rows(), List.of(
                SummaryLine.count("Loans", ids.size()),
                SummaryLine.count("Repayments", planned.stream().mapToLong(l -> l.repayments().size()).sum()),
                SummaryLine.amount("Lent", principal, chama.format(principal)),
                SummaryLine.amount("Outstanding", outstanding, chama.format(outstanding))), Map.of());
    }

    @Override
    public Optional<String> undoBlocked(UUID chamaId, Batch batch) {
        return loans.undoBlocked(chamaId, batch.id());
    }

    @Override
    public void undo(UUID chamaId, UUID actorUserId, Batch batch) {
        loans.undoLoans(chamaId, actorUserId, batch.id());
    }
}
