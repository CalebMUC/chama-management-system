package ke.chama.modules.account.domain.model;

/**
 * What a savings product is for. The kind sets sensible defaults for a new product and where its
 * ledger account sits in the chart:
 * <ul>
 *   <li>DEPOSITS: the member's savings. Refunded on exit, backs loans, earns dividends.</li>
 *   <li>SHARE_CAPITAL: the member's stake in the chama (equity). Not refunded but transferred or sold
 *       on exit, earns dividends.</li>
 *   <li>WELFARE: a benevolent fund for members in need. Pooled: not refunded, no dividends.</li>
 *   <li>CUSTOM: anything else, e.g. holiday or school-fees savings.</li>
 * </ul>
 */
public enum SavingsProductKind {
    DEPOSITS(true, true, true, "LIABILITY", 2010),
    SHARE_CAPITAL(false, false, true, "EQUITY", 3100),
    WELFARE(false, false, false, "LIABILITY", 2010),
    CUSTOM(true, false, false, "LIABILITY", 2010);

    private final boolean refundableOnExit;
    private final boolean countsTowardLoanLimit;
    private final boolean earnsDividend;
    private final String ledgerAccountType;
    private final int firstLedgerCode;

    SavingsProductKind(boolean refundableOnExit, boolean countsTowardLoanLimit, boolean earnsDividend,
                       String ledgerAccountType, int firstLedgerCode) {
        this.refundableOnExit = refundableOnExit;
        this.countsTowardLoanLimit = countsTowardLoanLimit;
        this.earnsDividend = earnsDividend;
        this.ledgerAccountType = ledgerAccountType;
        this.firstLedgerCode = firstLedgerCode;
    }

    public boolean refundableOnExit() {
        return refundableOnExit;
    }

    public boolean countsTowardLoanLimit() {
        return countsTowardLoanLimit;
    }

    public boolean earnsDividend() {
        return earnsDividend;
    }

    /** LIABILITY (owed back to members) or EQUITY (share capital). */
    public String ledgerAccountType() {
        return ledgerAccountType;
    }

    /**
     * Ledger codes to try for a new product's account, in steps of 10: 2010-2090 for liabilities
     * (after "2000 Member savings"), 3100-3190 for share capital.
     */
    public int[] candidateLedgerCodes() {
        int[] codes = new int[9];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = firstLedgerCode + i * 10;
        }
        return codes;
    }
}
