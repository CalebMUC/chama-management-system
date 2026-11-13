package ke.chama.modules.dataimport.application.service.history;

import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.SummaryLine;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.TableView;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords.Batch;
import ke.chama.modules.dataimport.domain.model.history.ExpenseCheck;
import ke.chama.modules.dataimport.domain.model.history.ExpenseCheck.AccountRef;
import ke.chama.modules.dataimport.domain.model.history.ExpenseCheck.InvestmentRef;
import ke.chama.modules.dataimport.domain.model.history.ExpenseCheck.PlannedExpense;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Template;
import ke.chama.modules.dataimport.domain.model.history.Sheets.TemplateSheet;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;
import ke.chama.modules.expense.api.ExpenseHistory;
import ke.chama.modules.expense.api.ExpenseHistory.HistoricalExpense;
import ke.chama.modules.investment.api.InvestmentPortfolio;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.LedgerAccountSummary;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Step 6: expenses, each recorded as paid on its own day and charged to its expense account. */
@Component
class ExpenseImporter implements HistoryImporter {

    private final ExpenseHistory expenses;
    private final InvestmentPortfolio investments;
    private final Ledger ledger;

    ExpenseImporter(ExpenseHistory expenses, InvestmentPortfolio investments, Ledger ledger) {
        this.expenses = expenses;
        this.investments = investments;
        this.ledger = ledger;
    }

    @Override
    public Kind kind() {
        return Kind.EXPENSES;
    }

    private List<AccountRef> accounts(UUID chamaId) {
        return ledger.accountTotals(chamaId, null, null).stream()
                .filter(a -> LedgerAccountSummary.EXPENSE.equals(a.type()))
                .filter(a -> ledger.account(chamaId, a.code()).map(LedgerAccountSummary::active).orElse(false))
                .map(a -> new AccountRef(a.code(), a.name(), a.systemKey() == LedgerAccountKey.OPERATING_EXPENSES))
                .toList();
    }

    private List<InvestmentRef> investmentRefs(UUID chamaId) {
        return investments.fundedInvestments(chamaId).stream()
                .map(i -> new InvestmentRef(i.id(), i.name(), i.fundedOn())).toList();
    }

    @Override
    public List<SheetSpec> sheets(UUID chamaId, HistoryChama chama) {
        return List.of(ExpenseCheck.sheet(accounts(chamaId), investmentRefs(chamaId)));
    }

    @Override
    public Template template(UUID chamaId, HistoryChama chama) {
        return new Template(HistoryTemplates.readMe(
                "Step 6 of 7 - Expenses",
                "One row per payment the chama made for its running costs: bank charges, meeting venues, stationery, "
                        + "fees, an investment's bills, and so on. Do not list loans paid out, money put into investments "
                        + "or members' withdrawals here: those come in with loans (step 3) and investments (step 5).",
                "Pick the expense account for each (operating expenses if blank). If it was spent on an investment, "
                        + "name the investment so its profit takes it into account."),
                List.of(new TemplateSheet(sheets(chamaId, chama).getFirst(), List.of())));
    }

    @Override
    public Checked check(UUID chamaId, Workbook workbook, HistoryChama chama) {
        ExpenseCheck.Result result = ExpenseCheck.check(workbook, chama.withReferences(expenses.usedReferences(chamaId)),
                accounts(chamaId), investmentRefs(chamaId));
        List<PlannedExpense> planned = result.expenses();
        long total = planned.stream().mapToLong(PlannedExpense::amountMinor).sum();
        Map<String, long[]> byAccount = new TreeMap<>();
        Map<Integer, long[]> byYear = new TreeMap<>();
        for (PlannedExpense e : planned) {
            long[] a = byAccount.computeIfAbsent(e.account().label(), k -> new long[2]);
            a[0]++;
            a[1] += e.amountMinor();
            long[] y = byYear.computeIfAbsent(Year.from(e.paidOn()).getValue(), k -> new long[2]);
            y[0]++;
            y[1] += e.amountMinor();
        }
        List<List<String>> accountRows = new ArrayList<>();
        byAccount.forEach((label, t) -> accountRows.add(List.of(label, String.valueOf(t[0]), chama.format(t[1]))));
        List<List<String>> yearRows = new ArrayList<>();
        byYear.forEach((year, t) -> yearRows.add(List.of(String.valueOf(year), String.valueOf(t[0]), chama.format(t[1]))));
        return new Checked(planned, workbook.rows(ExpenseCheck.EXPENSES).size(), result.problems(), List.of(
                SummaryLine.count("Expenses", planned.size()),
                SummaryLine.amount("Total", total, chama.format(total))), List.of(
                new TableView("By expense account", List.of("Account", "Expenses", "Total"), accountRows),
                new TableView("By year", List.of("Year", "Expenses", "Total"), yearRows)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Applied apply(UUID chamaId, UUID actorUserId, UUID batchId, Checked checked, HistoryChama chama) {
        List<PlannedExpense> planned = (List<PlannedExpense>) checked.plan();
        String batch = batchId.toString();
        int count = expenses.importExpenses(chamaId, actorUserId, batchId, planned.stream()
                .map(e -> new HistoricalExpense(e.paidOn(), e.amountMinor(), e.payee(), e.description(),
                        e.account().code(), e.investment() == null ? null : e.investment().id(), e.method(),
                        e.reference() == null && !e.method().equals("CASH")
                                ? ExpenseCheck.madeUpReference(batch, e.row()) : e.reference()))
                .toList());
        long total = planned.stream().mapToLong(PlannedExpense::amountMinor).sum();
        return new Applied(checked.rows(), List.of(SummaryLine.count("Expenses", count),
                SummaryLine.amount("Total", total, chama.format(total))), Map.of());
    }

    @Override
    public Optional<String> undoBlocked(UUID chamaId, Batch batch) {
        return Optional.empty();
    }

    @Override
    public void undo(UUID chamaId, UUID actorUserId, Batch batch) {
        expenses.undoExpenses(chamaId, actorUserId, batch.id());
    }
}
