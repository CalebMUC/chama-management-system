package ke.chama.modules.dataimport.application.service.history;

import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.SummaryLine;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.TableView;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords.Batch;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama;
import ke.chama.modules.dataimport.domain.model.history.ReconciliationCheck;
import ke.chama.modules.dataimport.domain.model.history.ReconciliationCheck.Balance;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Template;
import ke.chama.modules.dataimport.domain.model.history.Sheets.TemplateSheet;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.api.PostingRequest.PostingLine;
import ke.chama.shared.domain.Money;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Step 7: brings the cash, bank and M-Pesa balances in line with the chama's statements. The
 * difference from what the imported records add up to is posted, one entry per date, against the
 * retained surplus, with a memo saying why.
 */
@Component
class ReconciliationImporter implements HistoryImporter {

    static final String SOURCE = "HISTORY_RECONCILIATION";
    static final String ENTRIES = "entries";
    static final String UNDO_REASON = "Historical import undone";

    private final Ledger ledger;

    ReconciliationImporter(Ledger ledger) {
        this.ledger = ledger;
    }

    @Override
    public Kind kind() {
        return Kind.RECONCILIATION;
    }

    @Override
    public List<SheetSpec> sheets(UUID chamaId, HistoryChama chama) {
        return List.of(ReconciliationCheck.sheet());
    }

    @Override
    public Template template(UUID chamaId, HistoryChama chama) {
        List<List<Object>> rows = new ArrayList<>();
        for (String account : ReconciliationCheck.ACCOUNTS) {
            rows.add(new ArrayList<>(java.util.Arrays.asList(account, chama.today(), null)));
        }
        return new Template(HistoryTemplates.readMe(
                "Step 7 of 7 - Check the balances",
                "Do this last. For each account, put what it really held on a day: the bank statement's closing "
                        + "balance, the M-Pesa statement's, and a count of the cash. The preview shows what the imported "
                        + "records add up to on that day and the difference.",
                "Importing posts each difference as one adjustment against the chama's retained surplus, so the books "
                        + "match the statements. Differences usually come from money the chama held before its records "
                        + "start, or bank interest and charges nobody wrote down. A large difference is worth looking "
                        + "into first: a missing contribution, loan or expense is better imported than adjusted."),
                List.of(new TemplateSheet(ReconciliationCheck.sheet(), rows)));
    }

    /** One balance, with what the books say. */
    private record Line(Balance balance, long systemMinor) {
        long differenceMinor() {
            return balance.actualMinor() - systemMinor;
        }
    }

    @Override
    public Checked check(UUID chamaId, Workbook workbook, HistoryChama chama) {
        ReconciliationCheck.Result result = ReconciliationCheck.check(workbook, chama);
        List<Line> lines = new ArrayList<>();
        for (Balance b : result.balances()) {
            lines.add(new Line(b, balanceOn(chamaId, b.account(), b.asAt())));
        }
        long net = lines.stream().mapToLong(Line::differenceMinor).sum();
        List<List<String>> rows = lines.stream()
                .sorted(Comparator.comparing((Line l) -> l.balance().asAt()).thenComparing(l -> l.balance().account()))
                .map(l -> List.of(ReconciliationCheck.label(l.balance().account()), l.balance().asAt().toString(),
                        chama.format(l.systemMinor()), chama.format(l.balance().actualMinor()),
                        l.differenceMinor() == 0 ? "Matches" : signed(l.differenceMinor(), chama)))
                .toList();
        return new Checked(lines, workbook.rows(ReconciliationCheck.BALANCES).size(), result.problems(), List.of(
                SummaryLine.count("Balances", lines.size()),
                SummaryLine.count("To adjust", lines.stream().filter(l -> l.differenceMinor() != 0).count()),
                SummaryLine.amount("Net adjustment", net, signed(net, chama))),
                List.of(new TableView("Balances", List.of("Account", "As at", "In the books", "Actual", "Difference"),
                        rows)));
    }

    private static String signed(long minor, HistoryChama chama) {
        return (minor > 0 ? "+" : minor < 0 ? "-" : "") + chama.format(Math.abs(minor));
    }

    /** The account's balance at the end of {@code day}. */
    private long balanceOn(UUID chamaId, String account, LocalDate day) {
        LedgerAccountKey key = LedgerAccountKey.valueOf(account);
        return ledger.accountTotals(chamaId, null, day).stream()
                .filter(a -> a.systemKey() == key).mapToLong(Ledger.AccountTotal::balanceMinor).sum();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Applied apply(UUID chamaId, UUID actorUserId, UUID batchId, Checked checked, HistoryChama chama) {
        Currency currency = Currency.getInstance(chama.currency());
        Map<LocalDate, List<Balance>> byDate = new TreeMap<>();
        for (Line l : (List<Line>) checked.plan()) {
            byDate.computeIfAbsent(l.balance().asAt(), k -> new ArrayList<>()).add(l.balance());
        }
        Map<String, String> entries = new LinkedHashMap<>();
        long net = 0;
        int adjusted = 0;
        // Dates oldest first, each worked out again after the earlier ones were posted.
        for (Map.Entry<LocalDate, List<Balance>> e : byDate.entrySet()) {
            List<PostingLine> postings = new ArrayList<>();
            long surplus = 0;
            for (Balance b : e.getValue()) {
                long difference = b.actualMinor() - balanceOn(chamaId, b.account(), e.getKey());
                if (difference == 0) {
                    continue;
                }
                LedgerAccountKey key = LedgerAccountKey.valueOf(b.account());
                Money amount = Money.of(Math.abs(difference), currency);
                postings.add((difference > 0 ? PostingLine.debit(key, amount) : PostingLine.credit(key, amount))
                        .withMemo("Statement balance " + chama.format(b.actualMinor())));
                surplus += difference;
                adjusted++;
            }
            if (postings.isEmpty()) {
                continue;
            }
            if (surplus != 0) {
                Money amount = Money.of(Math.abs(surplus), currency);
                postings.add((surplus > 0 ? PostingLine.credit(LedgerAccountKey.RETAINED_SURPLUS, amount)
                        : PostingLine.debit(LedgerAccountKey.RETAINED_SURPLUS, amount))
                        .withMemo("Not explained by the imported records"));
            } else {
                // Differences that cancel out between accounts are a transfer; no surplus line is needed.
                if (postings.size() < 2) {
                    continue;
                }
            }
            UUID entry = ledger.post(new PostingRequest(chamaId, e.getKey(),
                    "Balances brought in line with the chama's statements as at " + e.getKey(), null, SOURCE,
                    batchId + ":" + e.getKey(), actorUserId, postings));
            entries.put(e.getKey().toString(), entry.toString());
            net += surplus;
        }
        return new Applied(checked.rows(), List.of(SummaryLine.count("Balances adjusted", adjusted),
                SummaryLine.amount("Net adjustment", net, signed(net, chama))), Map.of(ENTRIES, entries));
    }

    @Override
    public Optional<String> undoBlocked(UUID chamaId, Batch batch) {
        return Optional.empty();
    }

    @Override
    public void undo(UUID chamaId, UUID actorUserId, Batch batch) {
        batch.undoData().getOrDefault(ENTRIES, Map.of()).values()
                .forEach(entry -> ledger.reverseOnOriginalDate(chamaId, UUID.fromString(entry), actorUserId, UNDO_REASON));
    }
}
