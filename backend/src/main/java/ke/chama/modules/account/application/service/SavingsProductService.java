package ke.chama.modules.account.application.service;

import ke.chama.modules.account.api.SavingsProductSummary;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.account.application.port.in.AccountViews.ProductView;
import ke.chama.modules.account.application.port.in.SavingsProductUseCases;
import ke.chama.modules.account.application.port.out.AccountRepository;
import ke.chama.modules.account.domain.exception.AccountExceptions.DuplicateProductCodeException;
import ke.chama.modules.account.domain.exception.AccountExceptions.InvalidProductException;
import ke.chama.modules.account.domain.exception.AccountExceptions.NoLedgerCodeAvailableException;
import ke.chama.modules.account.domain.exception.AccountExceptions.ProductNotFoundException;
import ke.chama.modules.account.domain.model.MemberAccount;
import ke.chama.modules.account.domain.model.SavingsProduct;
import ke.chama.modules.account.domain.model.SavingsProductKind;
import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountSummary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Savings products: adding them (each with its own member-ledger account), changing their terms,
 * and switching them off. The default product is set up and kept in step with the chama's
 * settings by {@link DefaultSavingsProduct}.
 */
@Service
@Transactional
public class SavingsProductService implements SavingsProducts, SavingsProductUseCases {

    static final String AUDIT_TARGET = "SAVINGS_PRODUCT";

    private final AccountRepository repository;
    private final MemberAccountService accounts;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public SavingsProductService(AccountRepository repository, MemberAccountService accounts, ChamaDirectory chamas,
                                 Ledger ledger, AuditTrail auditTrail, Clock clock) {
        this.repository = repository;
        this.accounts = accounts;
        this.chamas = chamas;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    // ---- SavingsProducts (for other modules) --------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<SavingsProductSummary> products(UUID chamaId) {
        return repository.products(chamaId).stream().map(SavingsProductService::summary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SavingsProductSummary defaultProduct(UUID chamaId) {
        return repository.defaultProduct(chamaId).map(SavingsProductService::summary)
                .orElseThrow(() -> new IllegalStateException("Chama " + chamaId + " has no default savings product"));
    }

    // ---- use cases ------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ProductView> listProducts(UUID chamaId) {
        String currency = currency(chamaId);
        Map<UUID, Long> members = openAccountsPerProduct(chamaId);
        return repository.products(chamaId).stream().sorted(DISPLAY_ORDER)
                .map(p -> view(p, currency, members)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductView product(UUID chamaId, UUID productId) {
        SavingsProduct product = load(chamaId, productId);
        return view(product, currency(chamaId), openAccountsPerProduct(chamaId));
    }

    @Override
    public ProductView create(UUID chamaId, UUID actorUserId, String code, SavingsProductKind kind,
                             String ledgerAccountCode, SavingsProduct.Terms terms) {
        String normalizedCode = SavingsProduct.normalizeCode(code);
        if (repository.productByCode(chamaId, normalizedCode).isPresent()) {
            throw new DuplicateProductCodeException(normalizedCode);
        }
        // Validates the terms before the ledger account is created.
        SavingsProduct draft = SavingsProduct.create(chamaId, normalizedCode, kind, terms, "-", actorUserId,
                clock.instant());
        LedgerAccountSummary account = ledger.openMemberLedgerAccount(chamaId, ledgerCode(chamaId, kind, ledgerAccountCode),
                draft.name(), kind.ledgerAccountType(), actorUserId);
        SavingsProduct product = SavingsProduct.create(chamaId, normalizedCode, kind, terms, account.code(),
                actorUserId, clock.instant());
        repository.insertProduct(product);
        if (product.mandatory()) {
            accounts.openMandatoryAccounts(chamaId);
        }
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "SAVINGS_PRODUCT_CREATED", AUDIT_TARGET, product.id())
                .with(details(product)));
        return product(chamaId, product.id());
    }

    @Override
    public ProductView update(UUID chamaId, UUID actorUserId, UUID productId, SavingsProduct.Terms terms) {
        SavingsProduct before = load(chamaId, productId);
        SavingsProduct after = before.update(terms, clock.instant());
        save(after);
        if (after.mandatory() && after.active()) {
            accounts.openMandatoryAccounts(chamaId);
        }
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "SAVINGS_PRODUCT_UPDATED", AUDIT_TARGET, productId)
                .with(Map.of("before", details(before), "after", details(after))));
        return product(chamaId, productId);
    }

    @Override
    public ProductView setActive(UUID chamaId, UUID actorUserId, UUID productId, boolean active) {
        SavingsProduct product = load(chamaId, productId);
        if (product.active() != active) {
            SavingsProduct changed = product.withActive(active, clock.instant());
            save(changed);
            if (changed.mandatory() && active) {
                accounts.openMandatoryAccounts(chamaId);
            }
            auditTrail.record(AuditEntry.of(chamaId, actorUserId,
                    active ? "SAVINGS_PRODUCT_ACTIVATED" : "SAVINGS_PRODUCT_DEACTIVATED", AUDIT_TARGET, productId)
                    .with(Map.of("code", product.code(), "name", product.name())));
        }
        return product(chamaId, productId);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** The requested code if it is free, else the first free code for the kind. */
    private String ledgerCode(UUID chamaId, SavingsProductKind kind, String requested) {
        if (requested != null && !requested.isBlank()) {
            String code = requested.trim().toUpperCase(Locale.ROOT);
            if (ledger.account(chamaId, code).isPresent()) {
                throw new InvalidProductException("Ledger account " + code + " already exists; choose an unused code "
                        + "or leave ledgerAccountCode out to get the next free one");
            }
            return code;
        }
        for (int candidate : kind.candidateLedgerCodes()) {
            String code = Integer.toString(candidate);
            if (ledger.account(chamaId, code).isEmpty()) {
                return code;
            }
        }
        throw new NoLedgerCodeAvailableException();
    }

    private void save(SavingsProduct product) {
        if (!repository.updateProduct(product)) {
            throw new OptimisticLockingFailureException("Savings product " + product.id() + " changed concurrently");
        }
    }

    SavingsProduct load(UUID chamaId, UUID productId) {
        return repository.product(chamaId, productId).orElseThrow(ProductNotFoundException::new);
    }

    private Map<UUID, Long> openAccountsPerProduct(UUID chamaId) {
        return repository.accounts(chamaId).stream().filter(MemberAccount::active)
                .collect(Collectors.groupingBy(MemberAccount::productId, Collectors.counting()));
    }

    private String currency(UUID chamaId) {
        return chamas.find(chamaId).map(ChamaSummary::currency).orElse("KES");
    }

    private ProductView view(SavingsProduct p, String currency, Map<UUID, Long> members) {
        long balance = ledger.memberBalances(p.chamaId(), p.ledgerAccountCode()).values().stream()
                .mapToLong(Long::longValue).sum();
        return new ProductView(p.id(), p.code(), p.name(), p.kind(), p.ledgerAccountCode(), currency,
                p.contributionMinor(), p.allocationPriority(), p.mandatory(), p.countsTowardLoanLimit(),
                p.refundableOnExit(), p.earnsDividend(), p.defaultProduct(), p.active(),
                members.getOrDefault(p.id(), 0L).intValue(), balance, p.createdAt(), p.updatedAt());
    }

    static SavingsProductSummary summary(SavingsProduct p) {
        return new SavingsProductSummary(p.id(), p.code(), p.name(), p.kind().name(), p.ledgerAccountCode(),
                p.contributionMinor(), p.allocationPriority(), p.mandatory(), p.countsTowardLoanLimit(),
                p.refundableOnExit(), p.earnsDividend(), p.defaultProduct(), p.active());
    }

    private static Map<String, Object> details(SavingsProduct p) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", p.code());
        details.put("name", p.name());
        details.put("kind", p.kind().name());
        details.put("ledgerAccountCode", p.ledgerAccountCode());
        details.put("contributionMinor", p.contributionMinor());
        details.put("allocationPriority", p.allocationPriority());
        details.put("mandatory", p.mandatory());
        details.put("countsTowardLoanLimit", p.countsTowardLoanLimit());
        details.put("refundableOnExit", p.refundableOnExit());
        details.put("earnsDividend", p.earnsDividend());
        details.put("active", p.active());
        return details;
    }

    /** Active products first, then by allocation priority and code. */
    static final Comparator<SavingsProduct> DISPLAY_ORDER = Comparator.comparing((SavingsProduct p) -> !p.active())
            .thenComparingInt(SavingsProduct::allocationPriority)
            .thenComparing(SavingsProduct::code);
}
