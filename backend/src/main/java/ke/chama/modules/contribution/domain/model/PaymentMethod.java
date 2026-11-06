package ke.chama.modules.contribution.domain.model;

/** How the money reached the chama; decides which ledger account it is posted to. */
public enum PaymentMethod {
    CASH,
    BANK,
    MPESA
}
