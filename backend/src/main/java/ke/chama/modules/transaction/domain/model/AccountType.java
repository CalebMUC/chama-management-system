package ke.chama.modules.transaction.domain.model;

import ke.chama.modules.transaction.api.EntrySide;

/**
 * The five kinds of account. Each grows on its normal side: assets and expenses with debits,
 * liabilities, equity and income with credits.
 */
public enum AccountType {
    ASSET(EntrySide.DEBIT),
    LIABILITY(EntrySide.CREDIT),
    EQUITY(EntrySide.CREDIT),
    INCOME(EntrySide.CREDIT),
    EXPENSE(EntrySide.DEBIT);

    private final EntrySide normalSide;

    AccountType(EntrySide normalSide) {
        this.normalSide = normalSide;
    }

    public EntrySide normalSide() {
        return normalSide;
    }

    /** The balance on the normal side: positive when the account holds what it is meant to. */
    public long balance(long debits, long credits) {
        return normalSide == EntrySide.DEBIT ? debits - credits : credits - debits;
    }
}
