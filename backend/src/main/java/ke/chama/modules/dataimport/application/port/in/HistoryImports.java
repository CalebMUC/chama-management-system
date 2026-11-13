package ke.chama.modules.dataimport.application.port.in;

import ke.chama.modules.dataimport.domain.model.Problem;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bringing over the history of a chama that ran for years before it used the system, one file per
 * module, in order: setup (join dates and the contribution schedule), contributions, loans,
 * penalties, investments, expenses, and finally a cash reconciliation. Each file is previewed (every
 * row checked, nothing saved), then imported all or nothing as one batch. The latest batch can be
 * undone. Not available once opening balances were imported (the two would count savings twice).
 */
public interface HistoryImports {

    enum Kind {
        SETUP,
        CONTRIBUTIONS,
        LOANS,
        PENALTIES,
        INVESTMENTS,
        EXPENSES,
        RECONCILIATION;

        /** As used in URLs and file names, e.g. "contributions". */
        public String slug() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromSlug(String slug) {
            return valueOf(slug.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    /** The .xlsx template for the kind, with the chama's members and products filled in. */
    byte[] template(UUID chamaId, Kind kind);

    /** Checks the file without saving anything. */
    PreviewView preview(UUID chamaId, Kind kind, byte[] file);

    /** Imports the file as one batch; refused if the preview would show any error. */
    BatchView importFile(UUID chamaId, UUID actorUserId, Kind kind, String fileName, byte[] file);

    /** Every batch, newest first, with which one can be undone. */
    List<BatchView> batches(UUID chamaId);

    /** Undoes the latest batch: its records are removed and its ledger entries reversed. */
    BatchView undo(UUID chamaId, UUID actorUserId, UUID batchId);

    /**
     * One total on a preview or batch.
     *
     * @param type  AMOUNT (value in minor units), COUNT, DATE or TEXT (both in {@code text})
     */
    record SummaryLine(String label, String type, Long value, String text) {

        public static SummaryLine amount(String label, long minor, String formatted) {
            return new SummaryLine(label, "AMOUNT", minor, formatted);
        }

        public static SummaryLine count(String label, long count) {
            return new SummaryLine(label, "COUNT", count, String.valueOf(count));
        }

        public static SummaryLine text(String label, String text) {
            return new SummaryLine(label, "TEXT", null, text);
        }

        public static SummaryLine date(String label, java.time.LocalDate date) {
            return new SummaryLine(label, "DATE", null, date == null ? "" : date.toString());
        }
    }

    /** A table to show on the preview, e.g. each member's totals. Cells are ready to display. */
    record TableView(String title, List<String> columns, List<List<String>> rows) {
    }

    record ProblemView(Problem.Severity severity, String sheet, Integer row, String column, String message) {
    }

    /**
     * @param importable true when there are no errors (warnings do not stop an import)
     * @param problems   at most {@link #MAX_PROBLEMS} of them; {@code errors} and {@code warnings} count all
     */
    record PreviewView(Kind kind,
                       boolean importable,
                       int rows,
                       long errors,
                       long warnings,
                       List<SummaryLine> summary,
                       List<TableView> tables,
                       List<ProblemView> problems) {
    }

    int MAX_PROBLEMS = 1_000;

    /** @param undoable whether {@link #undo} would accept it now; {@code undoBlockedReason} says why not */
    record BatchView(UUID id,
                     Kind kind,
                     String fileName,
                     int rowCount,
                     List<SummaryLine> summary,
                     UUID importedBy,
                     String importedByName,
                     Instant importedAt,
                     UUID undoneBy,
                     Instant undoneAt,
                     boolean undoable,
                     String undoBlockedReason) {
    }
}
