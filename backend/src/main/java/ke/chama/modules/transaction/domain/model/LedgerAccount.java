package ke.chama.modules.transaction.domain.model;

import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.InvalidAccountException;

import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An account in a chama's chart of accounts.
 *
 * @param systemKey    set for the accounts every chama has; null for accounts a chama added
 * @param memberLedger true when every line names a member, giving each member their own balance
 */
public record LedgerAccount(UUID id,
                            UUID chamaId,
                            String code,
                            String name,
                            AccountType type,
                            LedgerAccountKey systemKey,
                            Currency currency,
                            boolean memberLedger,
                            boolean active,
                            UUID createdBy,
                            Instant createdAt) {

    static final Pattern CODE = Pattern.compile("^[0-9A-Z][0-9A-Z-]{1,9}$");
    static final int MAX_NAME_LENGTH = 100;

    public LedgerAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chamaId, "chamaId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
    }

    /** An account a chama adds to its chart, e.g. "1030 Equity Bank savings account". */
    public static LedgerAccount custom(UUID chamaId, String code, String name, AccountType type, Currency currency,
                                       UUID createdBy, Instant now) {
        String normalizedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalizedCode).matches()) {
            throw new InvalidAccountException("Account code must be 2 to 10 letters, digits or hyphens, e.g. 1030");
        }
        if (name == null || name.isBlank() || name.trim().length() > MAX_NAME_LENGTH) {
            throw new InvalidAccountException("Account name is required and must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (type == null) {
            throw new InvalidAccountException("Account type is required");
        }
        return new LedgerAccount(UUID.randomUUID(), chamaId, normalizedCode, name.trim(), type, null, currency,
                false, true, createdBy, now);
    }

    static LedgerAccount system(UUID chamaId, String code, String name, AccountType type, LedgerAccountKey key,
                                boolean memberLedger, Currency currency, Instant now) {
        return new LedgerAccount(UUID.randomUUID(), chamaId, code, name, type, key, currency, memberLedger, true,
                null, now);
    }

    public long balance(long debits, long credits) {
        return type.balance(debits, credits);
    }
}
