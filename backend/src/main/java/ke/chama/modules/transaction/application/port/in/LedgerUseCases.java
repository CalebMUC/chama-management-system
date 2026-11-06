package ke.chama.modules.transaction.application.port.in;

import ke.chama.modules.transaction.application.port.in.LedgerViews.AccountView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.EntryView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.MemberBalancesView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.StatementView;
import ke.chama.modules.transaction.application.port.in.LedgerViews.TrialBalanceView;
import ke.chama.modules.transaction.domain.model.AccountType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What chama officials and members do with the ledger directly (the API endpoints). */
public interface LedgerUseCases {

    /** The chart of accounts with current balances. */
    List<AccountView> chart(UUID chamaId);

    AccountView createAccount(UUID chamaId, UUID actorUserId, String code, String name, AccountType type);

    AccountView setAccountActive(UUID chamaId, UUID actorUserId, UUID accountId, boolean active);

    /**
     * A manual entry, e.g. opening balances or bank charges. Lines name accounts by code.
     * Audit-logged.
     */
    EntryView postManual(UUID chamaId, UUID actorUserId, ManualEntry entry);

    EntryView reverseEntry(UUID chamaId, UUID actorUserId, UUID entryId, String reason);

    EntryView entry(UUID chamaId, UUID entryId);

    /** Newest first. Every filter is optional. */
    Page<EntryView> entries(UUID chamaId, LocalDate from, LocalDate to, String sourceType, Pageable pageable);

    StatementView statement(UUID chamaId, UUID accountId, UUID memberUserId, LocalDate from, LocalDate to);

    TrialBalanceView trialBalance(UUID chamaId, LocalDate asOf);

    MemberBalancesView memberBalances(UUID chamaId, UUID memberUserId);

    record ManualEntry(LocalDate entryDate, String description, String reference, List<ManualLine> lines) {
    }

    /** Exactly one of {@code debitMinor} and {@code creditMinor} is positive. */
    record ManualLine(String accountCode, long debitMinor, long creditMinor, UUID memberUserId, String memo) {
    }
}
