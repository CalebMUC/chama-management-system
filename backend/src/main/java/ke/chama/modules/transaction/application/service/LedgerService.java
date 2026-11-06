package ke.chama.modules.transaction.application.service;

import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.transaction.api.EntrySide;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.api.PostingRequest.PostingLine;
import ke.chama.modules.transaction.application.port.in.LedgerUseCases;
import ke.chama.modules.transaction.application.port.in.LedgerViews.AccountView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.EntryView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.LineView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.MemberBalancesView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.StatementLine;
import ke.chama.modules.transaction.application.port.in.LedgerViews.StatementView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.TrialBalanceRow;
import ke.chama.modules.transaction.application.port.in.LedgerViews.TrialBalanceView;
import ke.chama.modules.transaction.application.port.out.LedgerRepository;
import ke.chama.modules.transaction.application.port.out.LedgerRepository.StatementRow;
import ke.chama.modules.transaction.application.port.out.LedgerRepository.Totals;
import ke.chama.modules.transaction.domain.event.LedgerEntryPosted;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.AccountInactiveException;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.AccountNotFoundException;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.AlreadyReversedException;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.CannotReverseReversalException;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.CurrencyMismatchException;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.DuplicateAccountCodeException;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.EntryNotFoundException;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.InvalidAccountException;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.InvalidEntryException;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.MemberRequiredException;
import ke.chama.modules.transaction.domain.model.AccountType;
import ke.chama.modules.transaction.domain.model.JournalEntry;
import ke.chama.modules.transaction.domain.model.JournalLine;
import ke.chama.modules.transaction.domain.model.LedgerAccount;
import ke.chama.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The ledger: posting, reversing and reading balances. Every chama's books live here; other
 * modules post through {@link Ledger}, officials through {@link LedgerUseCases}.
 */
@Service
@Transactional
public class LedgerService implements Ledger, LedgerUseCases {

    static final String AUDIT_TARGET = "LEDGER_ENTRY";

    private final LedgerRepository repository;
    private final UserDirectory userDirectory;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    public LedgerService(LedgerRepository repository, UserDirectory userDirectory, AuditTrail auditTrail,
                         ApplicationEventPublisher events, Clock clock,
                         @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.repository = repository;
        this.userDirectory = userDirectory;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- Ledger (for other modules) ---------------------------------------------------------

    @Override
    public UUID post(PostingRequest request) {
        if (request.sourceId() != null) {
            var existing = repository.entryIdForSource(request.chamaId(), request.sourceType(), request.sourceId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        List<JournalLine> lines = request.lines().stream().map(line -> resolve(request.chamaId(), line)).toList();
        JournalEntry entry = JournalEntry.create(request.chamaId(), request.entryDate(), request.description(),
                request.reference(), request.sourceType(), request.sourceId(), null, request.recordedBy(), lines,
                today(), clock.instant());
        return save(entry).id();
    }

    @Override
    public UUID reverse(UUID chamaId, UUID entryId, UUID actorUserId, String reason) {
        JournalEntry original = repository.entry(chamaId, entryId).orElseThrow(EntryNotFoundException::new);
        if (original.isReversal()) {
            throw new CannotReverseReversalException();
        }
        if (repository.reversalOf(chamaId, entryId).isPresent()) {
            throw new AlreadyReversedException(original.number());
        }
        return save(original.reversal(reason, actorUserId, today(), clock.instant())).id();
    }

    @Override
    @Transactional(readOnly = true)
    public Money balance(UUID chamaId, LedgerAccountKey key) {
        LedgerAccount account = accountByKey(chamaId, key);
        Totals totals = repository.accountTotals(chamaId, account.id(), null, null);
        return Money.of(account.balance(totals.debits(), totals.credits()), account.currency());
    }

    @Override
    @Transactional(readOnly = true)
    public Money memberBalance(UUID chamaId, UUID memberUserId, LedgerAccountKey key) {
        LedgerAccount account = accountByKey(chamaId, key);
        if (!account.memberLedger()) {
            throw new MemberRequiredException("Account " + account.code() + " does not keep balances per member");
        }
        Totals totals = repository.accountTotals(chamaId, account.id(), memberUserId, null);
        return Money.of(account.balance(totals.debits(), totals.credits()), account.currency());
    }

    // ---- Use cases (for the API) -------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<AccountView> chart(UUID chamaId) {
        Map<UUID, Totals> totals = repository.totalsByAccount(chamaId, null);
        return repository.accounts(chamaId).stream()
                .map(account -> view(account, totals.getOrDefault(account.id(), Totals.ZERO)))
                .toList();
    }

    @Override
    public AccountView createAccount(UUID chamaId, UUID actorUserId, String code, String name, AccountType type) {
        Currency currency = chamaCurrency(chamaId);
        LedgerAccount account = LedgerAccount.custom(chamaId, code, name, type, currency, actorUserId, clock.instant());
        if (repository.accountByCode(chamaId, account.code()).isPresent()) {
            throw new DuplicateAccountCodeException(account.code());
        }
        repository.insertAccounts(List.of(account));
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "LEDGER_ACCOUNT_CREATED", "LEDGER_ACCOUNT", account.id())
                .with(Map.of("code", account.code(), "name", account.name(), "type", account.type().name())));
        return view(account, Totals.ZERO);
    }

    @Override
    public AccountView setAccountActive(UUID chamaId, UUID actorUserId, UUID accountId, boolean active) {
        LedgerAccount account = repository.accountById(chamaId, accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId.toString()));
        if (!active && account.systemKey() != null) {
            throw new InvalidAccountException("Account " + account.code() + " is used by the system and cannot be deactivated");
        }
        if (account.active() != active) {
            repository.setAccountActive(chamaId, accountId, active);
            auditTrail.record(AuditEntry.of(chamaId, actorUserId,
                    active ? "LEDGER_ACCOUNT_ACTIVATED" : "LEDGER_ACCOUNT_DEACTIVATED", "LEDGER_ACCOUNT", accountId)
                    .with(Map.of("code", account.code())));
        }
        LedgerAccount updated = repository.accountById(chamaId, accountId).orElseThrow();
        return view(updated, repository.accountTotals(chamaId, accountId, null, null));
    }

    @Override
    public EntryView postManual(UUID chamaId, UUID actorUserId, ManualEntry manual) {
        Currency currency = chamaCurrency(chamaId);
        List<PostingLine> lines = new ArrayList<>();
        for (ManualLine line : manual.lines() == null ? List.<ManualLine>of() : manual.lines()) {
            boolean debit = line.debitMinor() > 0;
            boolean credit = line.creditMinor() > 0;
            if (debit == credit) {
                throw new InvalidEntryException("Each line needs either a debit or a credit amount, not both");
            }
            lines.add(new PostingLine(null, line.accountCode(), debit ? EntrySide.DEBIT : EntrySide.CREDIT,
                    Money.of(debit ? line.debitMinor() : line.creditMinor(), currency), line.memberUserId(), line.memo()));
        }
        UUID entryId = post(new PostingRequest(chamaId, manual.entryDate(), manual.description(), manual.reference(),
                PostingRequest.SOURCE_MANUAL, null, actorUserId, lines));
        EntryView posted = entry(chamaId, entryId);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "LEDGER_ENTRY_POSTED", AUDIT_TARGET, entryId)
                .with(Map.of("entryNumber", posted.number(), "totalMinor", posted.totalMinor(),
                        "description", posted.description())));
        return posted;
    }

    @Override
    public EntryView reverseEntry(UUID chamaId, UUID actorUserId, UUID entryId, String reason) {
        UUID reversalId = reverse(chamaId, entryId, actorUserId, reason);
        EntryView reversal = entry(chamaId, reversalId);
        long originalNumber = repository.entry(chamaId, entryId).map(JournalEntry::number).orElse(0L);
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "LEDGER_ENTRY_REVERSED", AUDIT_TARGET, entryId)
                .with(Map.of("entryNumber", originalNumber, "reversalNumber", reversal.number(),
                        "reason", reason == null ? "" : reason.trim())));
        return reversal;
    }

    @Override
    @Transactional(readOnly = true)
    public EntryView entry(UUID chamaId, UUID entryId) {
        JournalEntry entry = repository.entry(chamaId, entryId).orElseThrow(EntryNotFoundException::new);
        return view(entry, accountsById(chamaId), repository.reversalOf(chamaId, entryId).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EntryView> entries(UUID chamaId, LocalDate from, LocalDate to, String sourceType, Pageable pageable) {
        Page<JournalEntry> page = repository.entries(chamaId, from, to, sourceType, pageable);
        Map<UUID, LedgerAccount> accounts = accountsById(chamaId);
        Map<UUID, UUID> reversals = repository.reversalsOf(chamaId, page.map(JournalEntry::id).getContent());
        return page.map(entry -> view(entry, accounts, reversals.get(entry.id())));
    }

    @Override
    @Transactional(readOnly = true)
    public StatementView statement(UUID chamaId, UUID accountId, UUID memberUserId, LocalDate from, LocalDate to) {
        LedgerAccount account = repository.accountById(chamaId, accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId.toString()));
        if (memberUserId != null && !account.memberLedger()) {
            throw new MemberRequiredException("Account " + account.code() + " does not keep balances per member");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidEntryException("'to' must not be before 'from'");
        }
        Totals before = from == null ? Totals.ZERO : repository.accountTotals(chamaId, accountId, memberUserId, from);
        long opening = account.balance(before.debits(), before.credits());
        long running = opening;
        List<StatementLine> lines = new ArrayList<>();
        for (StatementRow row : repository.statement(chamaId, accountId, memberUserId, from, to)) {
            running += account.balance(row.debitMinor(), row.creditMinor());
            lines.add(new StatementLine(row.entryId(), row.entryNumber(), row.entryDate(), row.description(),
                    row.reference(), row.sourceType(), row.debitMinor(), row.creditMinor(), running,
                    row.memberUserId(), row.memo()));
        }
        Totals all = repository.accountTotals(chamaId, accountId, memberUserId, null);
        return new StatementView(view(account, all), memberUserId, from, to, opening, running, lines);
    }

    @Override
    @Transactional(readOnly = true)
    public TrialBalanceView trialBalance(UUID chamaId, LocalDate asOf) {
        LocalDate date = asOf == null ? today() : asOf;
        Map<UUID, Totals> totals = repository.totalsByAccount(chamaId, date);
        List<TrialBalanceRow> rows = new ArrayList<>();
        long debits = 0;
        long credits = 0;
        for (LedgerAccount account : repository.accounts(chamaId)) {
            Totals t = totals.getOrDefault(account.id(), Totals.ZERO);
            long net = t.debits() - t.credits();
            long debit = Math.max(net, 0);
            long credit = Math.max(-net, 0);
            debits += debit;
            credits += credit;
            rows.add(new TrialBalanceRow(account.id(), account.code(), account.name(), account.type(), debit, credit));
        }
        return new TrialBalanceView(date, chamaCurrency(chamaId).getCurrencyCode(), rows, debits, credits,
                debits == credits);
    }

    @Override
    @Transactional(readOnly = true)
    public MemberBalancesView memberBalances(UUID chamaId, UUID memberUserId) {
        Money savings = memberBalance(chamaId, memberUserId, LedgerAccountKey.MEMBER_SAVINGS);
        Money loans = memberBalance(chamaId, memberUserId, LedgerAccountKey.LOANS_RECEIVABLE);
        return new MemberBalancesView(memberUserId, savings.currency().getCurrencyCode(), savings.minorUnits(),
                loans.minorUnits());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private JournalEntry save(JournalEntry entry) {
        JournalEntry numbered = entry.numbered(repository.nextEntryNumber(entry.chamaId()));
        repository.insertEntry(numbered);
        events.publishEvent(new LedgerEntryPosted(UUID.randomUUID(), numbered.recordedAt(), numbered.chamaId(),
                numbered.id(), numbered.number(), numbered.entryDate(), numbered.sourceType(), numbered.sourceId(),
                numbered.totalMinor()));
        return numbered;
    }

    private JournalLine resolve(UUID chamaId, PostingLine line) {
        LedgerAccount account;
        if (line.key() != null) {
            account = accountByKey(chamaId, line.key());
        } else if (line.code() != null && !line.code().isBlank()) {
            String code = line.code().trim().toUpperCase(Locale.ROOT);
            account = repository.accountByCode(chamaId, code).orElseThrow(() -> new AccountNotFoundException(code));
        } else {
            throw new InvalidEntryException("Every line must name an account");
        }
        if (!account.active()) {
            throw new AccountInactiveException(account.code());
        }
        if (line.side() == null || line.amount() == null) {
            throw new InvalidEntryException("Every line needs a side and an amount");
        }
        if (!line.amount().currency().equals(account.currency())) {
            throw new CurrencyMismatchException(account.code(), account.currency().getCurrencyCode());
        }
        if (account.memberLedger()) {
            if (line.memberUserId() == null) {
                throw new MemberRequiredException("Account " + account.code() + " (" + account.name()
                        + ") keeps a balance per member: say which member on the line");
            }
            if (userDirectory.findById(line.memberUserId()).isEmpty()) {
                throw new MemberRequiredException("Member " + line.memberUserId() + " is not in this chama");
            }
        } else if (line.memberUserId() != null) {
            throw new MemberRequiredException("Account " + account.code() + " does not keep balances per member");
        }
        String memo = line.memo() == null || line.memo().isBlank() ? null : line.memo().trim();
        return new JournalLine(account.id(), line.side(), line.amount().minorUnits(), line.memberUserId(), memo);
    }

    private LedgerAccount accountByKey(UUID chamaId, LedgerAccountKey key) {
        return repository.accountByKey(chamaId, key).orElseThrow(() -> new AccountNotFoundException(key.name()));
    }

    /** Every account of a chama is kept in the chama's currency. */
    private Currency chamaCurrency(UUID chamaId) {
        return accountByKey(chamaId, LedgerAccountKey.CASH).currency();
    }

    private Map<UUID, LedgerAccount> accountsById(UUID chamaId) {
        return repository.accounts(chamaId).stream().collect(Collectors.toMap(LedgerAccount::id, Function.identity()));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    private static AccountView view(LedgerAccount account, Totals totals) {
        return new AccountView(account.id(), account.code(), account.name(), account.type(), account.systemKey(),
                account.currency().getCurrencyCode(), account.memberLedger(), account.active(),
                account.balance(totals.debits(), totals.credits()));
    }

    private static EntryView view(JournalEntry entry, Map<UUID, LedgerAccount> accounts, UUID reversedBy) {
        List<LineView> lines = entry.lines().stream().map(line -> {
            LedgerAccount account = accounts.get(line.accountId());
            return new LineView(line.accountId(), account == null ? null : account.code(),
                    account == null ? null : account.name(), line.debitMinor(), line.creditMinor(),
                    line.memberUserId(), line.memo());
        }).toList();
        return new EntryView(entry.id(), entry.number(), entry.entryDate(), entry.description(), entry.reference(),
                entry.sourceType(), entry.sourceId(), entry.reversesEntryId(), reversedBy, entry.recordedBy(),
                entry.recordedAt(), entry.totalMinor(), lines);
    }
}
