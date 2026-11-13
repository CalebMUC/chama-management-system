package ke.chama.modules.dataimport.application.service.history;

import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.SummaryLine;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.TableView;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords.Batch;
import ke.chama.modules.dataimport.domain.model.Problem;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Template;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One kind of historical import (setup, contributions, loans, ...): its template, its checks, how
 * it is applied and how it is undone. {@code HistoryImportService} runs every kind the same way.
 */
public interface HistoryImporter {

    Kind kind();

    /** The sheets read back from a filled-in file. */
    List<SheetSpec> sheets(UUID chamaId, HistoryChama chama);

    Template template(UUID chamaId, HistoryChama chama);

    /** Checks the rows against the chama; saves nothing. */
    Checked check(UUID chamaId, Workbook workbook, HistoryChama chama);

    /** Applies a check without errors, in the caller's transaction. */
    Applied apply(UUID chamaId, UUID actorUserId, UUID batchId, Checked checked, HistoryChama chama);

    /** Why the batch cannot be undone now, if it cannot (e.g. a loan it created was repaid since). */
    Optional<String> undoBlocked(UUID chamaId, Batch batch);

    /** Removes what the batch created, reversing its ledger entries, in the caller's transaction. */
    void undo(UUID chamaId, UUID actorUserId, Batch batch);

    /**
     * @param plan    what {@link #apply} will do; its type is the importer's own
     * @param rows    data rows read from the file
     */
    record Checked(Object plan, int rows, List<Problem> problems, List<SummaryLine> summary, List<TableView> tables) {

        public Checked {
            problems = List.copyOf(problems);
            summary = List.copyOf(summary);
            tables = List.copyOf(tables);
        }

        public boolean importable() {
            return problems.stream().noneMatch(Problem::blocking);
        }
    }

    /** @param undoData what {@link #undo} must restore (see {@code HistoryImportRecords.Batch}) */
    record Applied(int rows, List<SummaryLine> summary, Map<String, Map<String, String>> undoData) {
    }
}
