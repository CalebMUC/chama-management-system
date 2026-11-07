package ke.chama.modules.dataimport.application.port.out;

import ke.chama.modules.dataimport.application.port.in.OpeningBalanceImports.ImportView;

import java.util.Optional;
import java.util.UUID;

/** The one opening-balance import per chama. Row-level security limits queries to the current chama. */
public interface ImportRecords {

    Optional<ImportView> find(UUID chamaId);

    void insert(UUID chamaId, ImportView record);
}
