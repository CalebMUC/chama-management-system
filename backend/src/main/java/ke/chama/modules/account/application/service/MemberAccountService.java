package ke.chama.modules.account.application.service;

import ke.chama.modules.account.api.MemberAccountSummary;
import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.application.port.in.AccountViews.AccountView;
import ke.chama.modules.account.application.port.in.AccountViews.MyAccountsView;
import ke.chama.modules.account.application.port.in.AccountViews.StatementLineView;
import ke.chama.modules.account.application.port.in.AccountViews.StatementView;
import ke.chama.modules.account.application.port.in.MemberAccountUseCases;
import ke.chama.modules.account.application.port.out.AccountRepository;
import ke.chama.modules.account.domain.exception.AccountExceptions.AccountExistsException;
import ke.chama.modules.account.domain.exception.AccountExceptions.AccountNotFoundException;
import ke.chama.modules.account.domain.exception.AccountExceptions.AccountStateException;
import ke.chama.modules.account.domain.exception.AccountExceptions.NotASeatedMemberException;
import ke.chama.modules.account.domain.exception.AccountExceptions.ProductInactiveException;
import ke.chama.modules.account.domain.exception.AccountExceptions.ProductNotFoundException;
import ke.chama.modules.account.domain.model.MemberAccount;
import ke.chama.modules.account.domain.model.SavingsProduct;
import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.Ledger.Statement;
import ke.chama.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Members' accounts. Accounts in mandatory products open by themselves, whenever accounts or
 * contributions are used ({@link #openMandatoryAccounts}), so a new member or a new mandatory
 * product never leaves anyone without an account. Balances come from the ledger, per product.
 */
@Service
@Transactional
public class MemberAccountService implements MemberAccounts, MemberAccountUseCases {

    static final String AUDIT_TARGET = "MEMBER_ACCOUNT";

    private final AccountRepository repository;
    private final MemberDirectory members;
    private final UserDirectory users;
    private final ChamaDirectory chamas;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final ZoneId zone;

    public MemberAccountService(AccountRepository repository, MemberDirectory members, UserDirectory users,
                                ChamaDirectory chamas, Ledger ledger, AuditTrail auditTrail, Clock clock,
                                @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.members = members;
        this.users = users;
        this.chamas = chamas;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- MemberAccounts (for other modules) ---------------------------------------------------

    /**
     * A member's account in the default product opens on the day they joined, so they owe every
     * period from then on. Accounts in a mandatory product added later open on the later of the
     * day they joined and the day the product was added.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void openMandatoryAccounts(UUID chamaId) {
        List<SavingsProduct> mandatory = repository.products(chamaId).stream()
                .filter(p -> p.active() && p.mandatory()).toList();
        if (mandatory.isEmpty()) {
            return;
        }
        List<MemberSummary> seated = members.seatedMembers(chamaId);
        Set<String> existing = new HashSet<>();
        repository.accounts(chamaId).forEach(a -> existing.add(a.memberUserId() + "/" + a.productId()));
        LocalDate today = today();
        List<MemberAccount> missing = new ArrayList<>();
        for (MemberSummary member : seated) {
            LocalDate joined = member.joinedAt() == null ? today : LocalDate.ofInstant(member.joinedAt(), zone);
            for (SavingsProduct product : mandatory) {
                if (existing.contains(member.userId() + "/" + product.id())) {
                    continue;
                }
                LocalDate added = LocalDate.ofInstant(product.createdAt(), zone);
                LocalDate openedOn = product.defaultProduct() || joined.isAfter(added) ? joined : added;
                missing.add(MemberAccount.open(chamaId, product.code(), member.memberNumber(), member.userId(),
                        product.id(), openedOn, null, clock.instant()));
            }
        }
        repository.insertAccounts(missing);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberAccountSummary> accounts(UUID chamaId) {
        return repository.accounts(chamaId).stream().map(MemberAccountService::summary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberAccountSummary> accountsOf(UUID chamaId, UUID memberUserId) {
        return repository.accountsOf(chamaId, memberUserId).stream().map(MemberAccountService::summary).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MemberAccountSummary ensureAccount(UUID chamaId, UUID memberUserId, UUID productId, LocalDate openedOn,
                                              UUID actorUserId) {
        var existing = repository.accountFor(chamaId, memberUserId, productId);
        if (existing.isPresent()) {
            return summary(existing.get());
        }
        SavingsProduct product = product(chamaId, productId);
        MemberSummary member = members.seatedMember(chamaId, memberUserId).orElseThrow(NotASeatedMemberException::new);
        MemberAccount account = MemberAccount.open(chamaId, product.code(), member.memberNumber(), memberUserId,
                productId, openedOn, actorUserId, clock.instant());
        repository.insertAccounts(List.of(account));
        audit(account, actorUserId, "MEMBER_ACCOUNT_OPENED", Map.of("productCode", product.code(),
                "openedOn", openedOn.toString()));
        return summary(repository.accountFor(chamaId, memberUserId, productId).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public long loanBackingSavingsMinor(UUID chamaId, UUID memberUserId) {
        return repository.products(chamaId).stream()
                .filter(SavingsProduct::countsTowardLoanLimit)
                .mapToLong(p -> ledger.memberBalance(chamaId, memberUserId, p.ledgerAccountCode()).minorUnits())
                .sum();
    }

    // ---- use cases ------------------------------------------------------------------------------

    @Override
    public List<AccountView> accounts(UUID chamaId, UUID memberUserId, UUID productId, MemberAccount.Status status) {
        openMandatoryAccounts(chamaId);
        List<MemberAccount> accounts = (memberUserId == null ? repository.accounts(chamaId)
                : repository.accountsOf(chamaId, memberUserId)).stream()
                .filter(a -> productId == null || a.productId().equals(productId))
                .filter(a -> status == null || a.status() == status)
                .toList();
        return views(chamaId, accounts);
    }

    @Override
    public AccountView account(UUID chamaId, UUID accountId) {
        return views(chamaId, List.of(load(chamaId, accountId))).getFirst();
    }

    @Override
    public StatementView statement(UUID chamaId, UUID accountId, LocalDate from, LocalDate to) {
        return statement(chamaId, load(chamaId, accountId), from, to);
    }

    @Override
    public MyAccountsView mine(UUID chamaId, UUID memberUserId) {
        openMandatoryAccounts(chamaId);
        List<AccountView> accounts = views(chamaId, repository.accountsOf(chamaId, memberUserId));
        return new MyAccountsView(currency(chamaId), accounts.stream().mapToLong(AccountView::balanceMinor).sum(),
                accounts);
    }

    @Override
    public StatementView myStatement(UUID chamaId, UUID memberUserId, UUID accountId, LocalDate from, LocalDate to) {
        MemberAccount account = load(chamaId, accountId);
        if (!account.memberUserId().equals(memberUserId)) {
            throw new AccountNotFoundException();
        }
        return statement(chamaId, account, from, to);
    }

    @Override
    public AccountView open(UUID chamaId, UUID actorUserId, UUID memberUserId, UUID productId) {
        MemberSummary member = members.seatedMember(chamaId, memberUserId).orElseThrow(NotASeatedMemberException::new);
        SavingsProduct product = product(chamaId, productId);
        if (!product.active()) {
            throw new ProductInactiveException(product.name());
        }
        var existing = repository.accountFor(chamaId, memberUserId, productId);
        if (existing.isPresent()) {
            if (!existing.get().active()) {
                throw new AccountStateException("Account " + existing.get().accountNumber()
                        + " exists but is closed; reopen it instead");
            }
            throw new AccountExistsException(existing.get().accountNumber());
        }
        MemberAccount account = MemberAccount.open(chamaId, product.code(), member.memberNumber(), memberUserId,
                productId, today(), actorUserId, clock.instant());
        repository.insertAccounts(List.of(account));
        audit(account, actorUserId, "MEMBER_ACCOUNT_OPENED", Map.of("productCode", product.code(),
                "openedOn", account.openedOn().toString()));
        return account(chamaId, account.id());
    }

    @Override
    public AccountView close(UUID chamaId, UUID actorUserId, UUID accountId, String reason) {
        MemberAccount account = load(chamaId, accountId);
        SavingsProduct product = product(chamaId, account.productId());
        if (product.mandatory() && product.active() && members.seatedMember(chamaId, account.memberUserId()).isPresent()) {
            throw new AccountStateException("Every member must have an account in " + product.name()
                    + "; it can only be closed once the member leaves the chama");
        }
        Money balance = ledger.memberBalance(chamaId, account.memberUserId(), product.ledgerAccountCode());
        if (!balance.isZero()) {
            throw new AccountStateException("Account " + account.accountNumber() + " still holds " + balance
                    + "; it can only be closed with a zero balance");
        }
        MemberAccount closed = account.close(actorUserId, reason, today());
        save(closed);
        audit(closed, actorUserId, "MEMBER_ACCOUNT_CLOSED", Map.of("productCode", product.code(),
                "reason", closed.closureReason()));
        return account(chamaId, accountId);
    }

    @Override
    public AccountView reopen(UUID chamaId, UUID actorUserId, UUID accountId) {
        MemberAccount account = load(chamaId, accountId);
        SavingsProduct product = product(chamaId, account.productId());
        if (!product.active()) {
            throw new ProductInactiveException(product.name());
        }
        members.seatedMember(chamaId, account.memberUserId()).orElseThrow(NotASeatedMemberException::new);
        MemberAccount reopened = account.reopen(actorUserId, today());
        save(reopened);
        audit(reopened, actorUserId, "MEMBER_ACCOUNT_REOPENED", Map.of("productCode", product.code()));
        return account(chamaId, accountId);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private StatementView statement(UUID chamaId, MemberAccount account, LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new AccountStateException("'to' must not be before 'from'");
        }
        SavingsProduct product = product(chamaId, account.productId());
        Statement s = ledger.memberStatement(chamaId, product.ledgerAccountCode(), account.memberUserId(), from, to);
        List<StatementLineView> lines = s.lines().stream()
                .map(l -> new StatementLineView(l.entryId(), l.entryNumber(), l.entryDate(), l.description(),
                        l.reference(), l.sourceType(), l.debitMinor(), l.creditMinor(), l.balanceMinor(), l.memo()))
                .toList();
        return new StatementView(views(chamaId, List.of(account)).getFirst(), from, to, s.openingBalanceMinor(),
                s.closingBalanceMinor(), lines);
    }

    /** Views with names and balances, loading each product's balances once. */
    private List<AccountView> views(UUID chamaId, List<MemberAccount> accounts) {
        if (accounts.isEmpty()) {
            return List.of();
        }
        String currency = currency(chamaId);
        Map<UUID, SavingsProduct> products = repository.products(chamaId).stream()
                .collect(Collectors.toMap(SavingsProduct::id, Function.identity()));
        Map<UUID, Map<UUID, Long>> balances = new HashMap<>();
        Map<UUID, MemberSummary> seated = members.seatedMembers(chamaId).stream()
                .collect(Collectors.toMap(MemberSummary::userId, Function.identity()));
        Map<UUID, UserSummary> others = users.findByIds(accounts.stream().map(MemberAccount::memberUserId)
                .filter(id -> !seated.containsKey(id)).collect(Collectors.toSet()));
        return accounts.stream().map(a -> {
            SavingsProduct p = products.get(a.productId());
            long balance = balances.computeIfAbsent(p.id(),
                    id -> ledger.memberBalances(chamaId, p.ledgerAccountCode())).getOrDefault(a.memberUserId(), 0L);
            MemberSummary m = seated.get(a.memberUserId());
            String name = m != null ? m.fullName().trim()
                    : others.containsKey(a.memberUserId()) ? others.get(a.memberUserId()).fullName() : null;
            return new AccountView(a.id(), a.accountNumber(), a.memberUserId(), name, m == null ? null : m.memberNumber(),
                    p.id(), p.code(), p.name(), p.kind(), a.status(), a.openedOn(), a.closedOn(), a.closureReason(),
                    currency, balance);
        }).toList();
    }

    private MemberAccount load(UUID chamaId, UUID accountId) {
        return repository.account(chamaId, accountId).orElseThrow(AccountNotFoundException::new);
    }

    private SavingsProduct product(UUID chamaId, UUID productId) {
        return repository.product(chamaId, productId).orElseThrow(ProductNotFoundException::new);
    }

    private void save(MemberAccount account) {
        if (!repository.updateAccount(account)) {
            throw new OptimisticLockingFailureException("Member account " + account.id() + " changed concurrently");
        }
    }

    private void audit(MemberAccount account, UUID actorUserId, String action, Map<String, ?> details) {
        Map<String, Object> all = new HashMap<>(details);
        all.put("accountNumber", account.accountNumber());
        all.put("memberUserId", account.memberUserId().toString());
        auditTrail.record(AuditEntry.of(account.chamaId(), actorUserId, action, AUDIT_TARGET, account.id()).with(all));
    }

    private String currency(UUID chamaId) {
        return chamas.find(chamaId).map(ChamaSummary::currency).orElse(Currency.getInstance("KES").getCurrencyCode());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    static MemberAccountSummary summary(MemberAccount a) {
        return new MemberAccountSummary(a.id(), a.accountNumber(), a.memberUserId(), a.productId(), a.active(),
                a.openedOn(), a.closedOn());
    }
}
