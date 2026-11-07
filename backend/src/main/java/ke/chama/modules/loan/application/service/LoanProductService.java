package ke.chama.modules.loan.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.api.LoanRules;
import ke.chama.modules.chama.domain.event.ChamaCreated;
import ke.chama.modules.chama.domain.event.ChamaSettingsChanged;
import ke.chama.modules.loan.application.port.in.LoanProductUseCases;
import ke.chama.modules.loan.application.port.out.LoanProductRepository;
import ke.chama.modules.loan.domain.exception.LoanExceptions.DuplicateLoanProductCodeException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.LoanProductInactiveException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.LoanProductNotFoundException;
import ke.chama.modules.loan.domain.model.LoanProduct;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loan products. The default product ("Normal loan") is created with the chama and follows the
 * chama's loan settings; chamas created before products existed got theirs from migration V20.
 */
@Service
@Transactional
public class LoanProductService implements LoanProductUseCases {

    static final String AUDIT_TARGET = "LOAN_PRODUCT";

    private final LoanProductRepository repository;
    private final ChamaDirectory chamas;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public LoanProductService(LoanProductRepository repository, ChamaDirectory chamas, AuditTrail auditTrail,
                              Clock clock) {
        this.repository = repository;
        this.chamas = chamas;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    // ---- use cases ------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<LoanProductView> products(UUID chamaId) {
        String currency = currency(chamaId);
        return repository.products(chamaId).stream().map(p -> view(p, currency)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LoanProductView product(UUID chamaId, UUID productId) {
        return view(load(chamaId, productId), currency(chamaId));
    }

    @Override
    public LoanProductView create(UUID chamaId, UUID actorUserId, String code, LoanProduct.Terms terms) {
        LoanProduct product = LoanProduct.create(chamaId, code, terms, actorUserId, clock.instant());
        if (repository.productByCode(chamaId, product.code()).isPresent()) {
            throw new DuplicateLoanProductCodeException(product.code());
        }
        repository.insert(product);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "LOAN_PRODUCT_CREATED", AUDIT_TARGET, product.id())
                .with(details(product)));
        return view(product, currency(chamaId));
    }

    @Override
    public LoanProductView update(UUID chamaId, UUID actorUserId, UUID productId, LoanProduct.Terms terms) {
        LoanProduct before = load(chamaId, productId);
        LoanProduct after = before.update(terms, clock.instant());
        save(after);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "LOAN_PRODUCT_UPDATED", AUDIT_TARGET, productId)
                .with(Map.of("before", details(before), "after", details(after))));
        return product(chamaId, productId);
    }

    @Override
    public LoanProductView setActive(UUID chamaId, UUID actorUserId, UUID productId, boolean active) {
        LoanProduct product = load(chamaId, productId);
        if (product.active() != active) {
            save(product.withActive(active, clock.instant()));
            auditTrail.record(AuditEntry.of(chamaId, actorUserId,
                    active ? "LOAN_PRODUCT_ACTIVATED" : "LOAN_PRODUCT_DEACTIVATED", AUDIT_TARGET, productId)
                    .with(Map.of("code", product.code(), "name", product.name())));
        }
        return product(chamaId, productId);
    }

    // ---- for LoanService ------------------------------------------------------------------------

    /** The product a member applies under: the one named, or the default; it must be offered. */
    LoanProduct forApplication(UUID chamaId, UUID productId) {
        LoanProduct product = productId == null ? defaultProduct(chamaId) : load(chamaId, productId);
        if (!product.active()) {
            throw new LoanProductInactiveException(product.name());
        }
        return product;
    }

    LoanProduct defaultProduct(UUID chamaId) {
        return repository.defaultProduct(chamaId)
                .orElseThrow(() -> new IllegalStateException("Chama " + chamaId + " has no default loan product"));
    }

    Map<UUID, LoanProduct> byId(UUID chamaId) {
        Map<UUID, LoanProduct> byId = new LinkedHashMap<>();
        repository.products(chamaId).forEach(p -> byId.put(p.id(), p));
        return byId;
    }

    // ---- the default product --------------------------------------------------------------------

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(ChamaCreated event) {
        if (repository.defaultProduct(event.chamaId()).isEmpty()) {
            repository.insert(LoanProduct.defaultFrom(event.chamaId(), rules(event.chamaId()), clock.instant()));
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(ChamaSettingsChanged event) {
        LoanRules rules = rules(event.chamaId());
        repository.defaultProduct(event.chamaId())
                .filter(product -> !product.followsSettings(rules))
                .ifPresent(product -> save(product.followSettings(rules, clock.instant())));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private LoanRules rules(UUID chamaId) {
        return chamas.loanRules(chamaId).orElseThrow();
    }

    private LoanProduct load(UUID chamaId, UUID productId) {
        return repository.product(chamaId, productId).orElseThrow(LoanProductNotFoundException::new);
    }

    private void save(LoanProduct product) {
        if (!repository.update(product)) {
            throw new OptimisticLockingFailureException("Loan product " + product.id() + " changed concurrently");
        }
    }

    private String currency(UUID chamaId) {
        return chamas.find(chamaId).map(ChamaSummary::currency).orElse("KES");
    }

    private static LoanProductView view(LoanProduct p, String currency) {
        return new LoanProductView(p.id(), p.code(), p.name(), currency, p.interestBasisPoints(), p.maxTermMonths(),
                p.maxMultiplier(), p.minMembershipMonths(), p.guarantorThresholdMinor(), p.guarantorsRequired(),
                p.defaultProduct(), p.active(), p.createdAt(), p.updatedAt());
    }

    private static Map<String, Object> details(LoanProduct p) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", p.code());
        details.put("name", p.name());
        details.put("interestBasisPoints", p.interestBasisPoints());
        details.put("maxTermMonths", p.maxTermMonths());
        details.put("maxMultiplier", p.maxMultiplier());
        details.put("minMembershipMonths", p.minMembershipMonths());
        details.put("guarantorThresholdMinor", p.guarantorThresholdMinor());
        details.put("guarantorsRequired", p.guarantorsRequired());
        details.put("active", p.active());
        return details;
    }
}
