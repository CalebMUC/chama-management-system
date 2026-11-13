package ke.chama.modules.dataimport.application.port.out;

import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.SummaryLine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The chama's historical-import batches. Row-level security limits queries to the current chama. */
public interface HistoryImportRecords {

    /**
     * @param undoData what undoing the batch must restore, in groups of key to value, e.g.
     *                 "joinDates" to (member user id to the date before)
     */
    record Batch(UUID id, Kind kind, String fileName, int rowCount, List<SummaryLine> summary,
                 Map<String, Map<String, String>> undoData, UUID importedBy, Instant importedAt, UUID undoneBy,
                 Instant undoneAt) {

        public Batch {
            summary = List.copyOf(summary);
            undoData = undoData == null ? Map.of() : Map.copyOf(undoData);
        }

        public boolean active() {
            return undoneAt == null;
        }
    }

    void insert(UUID chamaId, Batch batch);

    /** Newest first. */
    List<Batch> all(UUID chamaId);

    Optional<Batch> find(UUID chamaId, UUID batchId);

    /** False if it was already undone (two undos at once). */
    boolean markUndone(UUID chamaId, UUID batchId, UUID undoneBy, Instant undoneAt);
}
