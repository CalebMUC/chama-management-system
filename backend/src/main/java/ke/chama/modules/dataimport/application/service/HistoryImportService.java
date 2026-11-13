package ke.chama.modules.dataimport.application.service;

import ke.chama.modules.account.api.MemberAccountSummary;
import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.dataimport.application.port.in.HistoryImports;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords.Batch;
import ke.chama.modules.dataimport.application.port.out.HistoryWorkbookFormat;
import ke.chama.modules.dataimport.application.port.out.ImportRecords;
import ke.chama.modules.dataimport.application.service.history.HistoryImporter;
import ke.chama.modules.dataimport.application.service.history.HistoryImporter.Applied;
import ke.chama.modules.dataimport.application.service.history.HistoryImporter.Checked;
import ke.chama.modules.dataimport.domain.exception.ImportExceptions.HistoryImportNotAllowedException;
import ke.chama.modules.dataimport.domain.exception.ImportExceptions.ImportBatchNotFoundException;
import ke.chama.modules.dataimport.domain.exception.ImportExceptions.ImportHasProblemsException;
import ke.chama.modules.dataimport.domain.exception.ImportExceptions.UndoNotAllowedException;
import ke.chama.modules.dataimport.domain.model.Problem;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.MemberRef;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.ProductRef;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs every kind of historical import the same way: the chama's prerequisites (opening balances
 * not imported; the setup first), the kind's own checks, one batch per file, undo latest first.
 */
@Service
@Transactional
public class HistoryImportService implements HistoryImports {

    static final String AUDIT_TARGET = "IMPORT";

    private final Map<Kind, HistoryImporter> importers = new EnumMap<>(Kind.class);
    private final HistoryWorkbookFormat workbooks;
    private final HistoryImportRecords records;
    private final ImportRecords openingBalances;
    private final ChamaDirectory chamas;
    private final MemberDirectory members;
    private final MemberAccounts memberAccounts;
    private final SavingsProducts savingsProducts;
    private final UserDirectory users;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final ZoneId zone;

    public HistoryImportService(List<HistoryImporter> importers, HistoryWorkbookFormat workbooks,
                                HistoryImportRecords records, ImportRecords openingBalances, ChamaDirectory chamas,
                                MemberDirectory members, MemberAccounts memberAccounts, SavingsProducts savingsProducts,
                                UserDirectory users, AuditTrail auditTrail, Clock clock,
                                @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        importers.forEach(i -> this.importers.put(i.kind(), i));
        this.workbooks = workbooks;
        this.records = records;
        this.openingBalances = openingBalances;
        this.chamas = chamas;
        this.members = members;
        this.memberAccounts = memberAccounts;
        this.savingsProducts = savingsProducts;
        this.users = users;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] template(UUID chamaId, Kind kind) {
        return workbooks.write(importer(kind).template(chamaId, chama(chamaId)));
    }

    @Override
    @Transactional(readOnly = true)
    public PreviewView preview(UUID chamaId, Kind kind, byte[] file) {
        List<Problem> problems = new ArrayList<>();
        prerequisite(chamaId, kind).ifPresent(reason -> problems.add(Problem.error(null, null, null, reason)));
        Checked checked = check(chamaId, kind, file);
        problems.addAll(checked.problems());
        long errors = problems.stream().filter(Problem::blocking).count();
        return new PreviewView(kind, errors == 0, checked.rows(), errors, problems.size() - errors, checked.summary(),
                checked.tables(), problems.stream()
                .sorted(Comparator.comparing((Problem p) -> !p.blocking()))
                .limit(MAX_PROBLEMS).map(HistoryImportService::view).toList());
    }

    @Override
    public BatchView importFile(UUID chamaId, UUID actorUserId, Kind kind, String fileName, byte[] file) {
        Optional<String> blocked = prerequisite(chamaId, kind);
        if (blocked.isPresent()) {
            throw new HistoryImportNotAllowedException(blocked.get());
        }
        Checked checked = check(chamaId, kind, file);
        if (!checked.importable()) {
            throw new ImportHasProblemsException(checked.problems().stream().filter(Problem::blocking).count());
        }
        UUID batchId = UUID.randomUUID();
        Applied applied = importer(kind).apply(chamaId, actorUserId, batchId, checked, chama(chamaId));
        Batch batch = new Batch(batchId, kind, fileName, applied.rows(), applied.summary(), applied.undoData(),
                actorUserId, clock.instant(), null, null);
        records.insert(chamaId, batch);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind.name());
        details.put("fileName", fileName == null ? "" : fileName);
        details.put("rows", applied.rows());
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "HISTORY_IMPORTED", AUDIT_TARGET, batchId).with(details));
        return views(chamaId).stream().filter(v -> v.id().equals(batchId)).findFirst().orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BatchView> batches(UUID chamaId) {
        return views(chamaId);
    }

    @Override
    public BatchView undo(UUID chamaId, UUID actorUserId, UUID batchId) {
        Batch batch = records.find(chamaId, batchId).orElseThrow(ImportBatchNotFoundException::new);
        Optional<String> blocked = undoBlocked(chamaId, batch, records.all(chamaId));
        if (blocked.isPresent()) {
            throw new UndoNotAllowedException(blocked.get());
        }
        importer(batch.kind()).undo(chamaId, actorUserId, batch);
        if (!records.markUndone(chamaId, batchId, actorUserId, clock.instant())) {
            throw new UndoNotAllowedException("This import was already undone");
        }
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "HISTORY_IMPORT_UNDONE", AUDIT_TARGET, batchId)
                .with(Map.of("kind", batch.kind().name(), "rows", batch.rowCount())));
        return views(chamaId).stream().filter(v -> v.id().equals(batchId)).findFirst().orElseThrow();
    }

    // ---- rules ------------------------------------------------------------------------------------

    /** Why this kind cannot be imported now, if it cannot. */
    private Optional<String> prerequisite(UUID chamaId, Kind kind) {
        var opening = openingBalances.find(chamaId);
        if (opening.isPresent()) {
            return Optional.of("Opening balances were imported for this chama (as at " + opening.get().asOf()
                    + "). Its history cannot be imported as well: savings would be counted twice.");
        }
        List<Batch> active = records.all(chamaId).stream().filter(Batch::active).toList();
        Optional<Batch> setup = active.stream().filter(b -> b.kind() == Kind.SETUP).findFirst();
        if (kind == Kind.SETUP && setup.isPresent()) {
            return Optional.of("The setup was already imported on " + LocalDate.ofInstant(setup.get().importedAt(), zone)
                    + ". To import it again, undo the imports since, then the setup.");
        }
        if (kind != Kind.SETUP && setup.isEmpty()) {
            return Optional.of("Import the setup first (step 1: when members joined and the contribution schedule). "
                    + "Everything else depends on it.");
        }
        return Optional.empty();
    }

    /** Only the latest active batch can be undone, and only while its importer allows. */
    private Optional<String> undoBlocked(UUID chamaId, Batch batch, List<Batch> all) {
        if (!batch.active()) {
            return Optional.of("This import was already undone");
        }
        Optional<Batch> latest = all.stream().filter(Batch::active).max(Comparator.comparing(Batch::importedAt));
        if (latest.isPresent() && !latest.get().id().equals(batch.id())) {
            return Optional.of("Undo the later imports first (latest first): the "
                    + latest.get().kind().slug() + " import of " + LocalDate.ofInstant(latest.get().importedAt(), zone));
        }
        return importer(batch.kind()).undoBlocked(chamaId, batch);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private Checked check(UUID chamaId, Kind kind, byte[] file) {
        HistoryChama chama = chama(chamaId);
        HistoryImporter importer = importer(kind);
        Workbook workbook = workbooks.read(file, fractionDigits(chama.currency()), importer.sheets(chamaId, chama));
        return importer.check(chamaId, workbook, chama);
    }

    private HistoryImporter importer(Kind kind) {
        HistoryImporter importer = importers.get(kind);
        if (importer == null) {
            throw new HistoryImportNotAllowedException("Importing " + kind.slug() + " is not available yet");
        }
        return importer;
    }

    /** The chama as every check sees it. References are added by the importers that need them. */
    HistoryChama chama(UUID chamaId) {
        ChamaSummary chama = chamas.find(chamaId).orElseThrow();
        Map<UUID, Set<UUID>> open = new HashMap<>();
        for (MemberAccountSummary a : memberAccounts.accounts(chamaId)) {
            if (a.active()) {
                open.computeIfAbsent(a.memberUserId(), k -> new HashSet<>()).add(a.productId());
            }
        }
        List<MemberRef> refs = members.seatedMembers(chamaId).stream()
                .filter(m -> m.memberNumber() != null)
                .sorted(Comparator.comparing(MemberSummary::memberNumber))
                .map(m -> new MemberRef(m.userId(), m.memberNumber(), name(m), m.email(),
                        m.joinedAt() == null ? null : LocalDate.ofInstant(m.joinedAt(), zone),
                        open.getOrDefault(m.userId(), Set.of())))
                .toList();
        List<ProductRef> products = savingsProducts.products(chamaId).stream()
                .map(p -> new ProductRef(p.id(), p.code(), p.name(), p.defaultProduct(), p.active())).toList();
        return new HistoryChama(LocalDate.now(clock.withZone(zone)), chama.currency(), refs, products, Set.of());
    }

    private List<BatchView> views(UUID chamaId) {
        List<Batch> all = records.all(chamaId);
        Set<UUID> people = new HashSet<>();
        all.forEach(b -> {
            people.add(b.importedBy());
            if (b.undoneBy() != null) {
                people.add(b.undoneBy());
            }
        });
        Map<UUID, UserSummary> names = people.isEmpty() ? Map.of() : users.findByIds(people);
        return all.stream().map(b -> {
            Optional<String> blocked = undoBlocked(chamaId, b, all);
            UserSummary by = names.get(b.importedBy());
            return new BatchView(b.id(), b.kind(), b.fileName(), b.rowCount(), b.summary(), b.importedBy(),
                    by == null ? null : (by.firstName() + " " + by.lastName()).trim(), b.importedAt(), b.undoneBy(),
                    b.undoneAt(), blocked.isEmpty(), blocked.orElse(null));
        }).toList();
    }

    private static String name(MemberSummary m) {
        String name = m.fullName().trim();
        return name.isEmpty() ? m.email() : name;
    }

    private static int fractionDigits(String currency) {
        return Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
    }

    private static ProblemView view(Problem p) {
        return new ProblemView(p.severity(), p.sheet(), p.row(), p.column(), p.message());
    }
}
