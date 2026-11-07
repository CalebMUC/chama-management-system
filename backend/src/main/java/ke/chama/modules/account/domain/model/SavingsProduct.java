package ke.chama.modules.account.domain.model;

import ke.chama.modules.account.domain.exception.AccountExceptions.DefaultProductRuleException;
import ke.chama.modules.account.domain.exception.AccountExceptions.InvalidProductException;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A pot the chama keeps for its members, e.g. "Deposits", "Share capital" or "Welfare fund". Each
 * member with an account owes {@code contributionMinor} per contribution period; balances are kept
 * on the product's own member-ledger account ({@code ledgerAccountCode}).
 * <p>
 * Every chama has one default product (Deposits, on "2000 Member savings"). It takes whatever a
 * payment does not owe elsewhere, so it is always active and every member has an account; its
 * contribution is the chama's contribution amount from the settings.
 */
public record SavingsProduct(UUID id,
                             UUID chamaId,
                             String code,
                             String name,
                             SavingsProductKind kind,
                             String ledgerAccountCode,
                             long contributionMinor,
                             int allocationPriority,
                             boolean mandatory,
                             boolean countsTowardLoanLimit,
                             boolean refundableOnExit,
                             boolean earnsDividend,
                             boolean defaultProduct,
                             boolean active,
                             UUID createdBy,
                             Instant createdAt,
                             Instant updatedAt,
                             long version) {

    public static final String DEFAULT_CODE = "DEP";
    public static final int DEFAULT_PRIORITY = 50;
    static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");
    static final int MAX_NAME_LENGTH = 60;

    public SavingsProduct {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chamaId, "chamaId");
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * What an official sets on a product. Null flags take the kind's defaults on creation.
     *
     * @param allocationPriority 1-99; within a period, payments clear lower numbers first
     */
    public record Terms(String name,
                        long contributionMinor,
                        int allocationPriority,
                        boolean mandatory,
                        Boolean countsTowardLoanLimit,
                        Boolean refundableOnExit,
                        Boolean earnsDividend) {
    }

    public static SavingsProduct create(UUID chamaId, String code, SavingsProductKind kind, Terms terms,
                                        String ledgerAccountCode, UUID createdBy, Instant now) {
        if (kind == null) {
            throw new InvalidProductException("The kind of product is required: DEPOSITS, SHARE_CAPITAL, WELFARE or CUSTOM");
        }
        String normalizedCode = normalizeCode(code);
        validate(terms);
        return new SavingsProduct(UUID.randomUUID(), chamaId, normalizedCode, terms.name().trim(), kind,
                ledgerAccountCode, terms.contributionMinor(), terms.allocationPriority(), terms.mandatory(),
                orDefault(terms.countsTowardLoanLimit(), kind.countsTowardLoanLimit()),
                orDefault(terms.refundableOnExit(), kind.refundableOnExit()),
                orDefault(terms.earnsDividend(), kind.earnsDividend()),
                false, true, createdBy, now, now, 0);
    }

    /** The product every chama starts with: Deposits, on the ledger's member savings account. */
    public static SavingsProduct defaultDeposits(UUID chamaId, String ledgerAccountCode, long contributionMinor,
                                                 Instant now) {
        SavingsProductKind kind = SavingsProductKind.DEPOSITS;
        return new SavingsProduct(UUID.randomUUID(), chamaId, DEFAULT_CODE, "Deposits", kind, ledgerAccountCode,
                contributionMinor, DEFAULT_PRIORITY, true, kind.countsTowardLoanLimit(), kind.refundableOnExit(),
                kind.earnsDividend(), true, true, null, now, now, 0);
    }

    /**
     * New terms. They apply to periods opened from now on; earlier periods keep what they asked.
     * The default product's contribution follows the chama's settings and it stays mandatory.
     */
    public SavingsProduct update(Terms terms, Instant now) {
        validate(terms);
        if (defaultProduct) {
            if (terms.contributionMinor() != contributionMinor) {
                throw new DefaultProductRuleException("The contribution to " + name
                        + " is the chama's contribution amount; change it in the chama's settings");
            }
            if (!terms.mandatory()) {
                throw new DefaultProductRuleException(name + " is the default product, so every member must have an account");
            }
        }
        return new SavingsProduct(id, chamaId, code, terms.name().trim(), kind, ledgerAccountCode,
                terms.contributionMinor(), terms.allocationPriority(), terms.mandatory(),
                orDefault(terms.countsTowardLoanLimit(), countsTowardLoanLimit),
                orDefault(terms.refundableOnExit(), refundableOnExit),
                orDefault(terms.earnsDividend(), earnsDividend),
                defaultProduct, active, createdBy, createdAt, now, version);
    }

    /** The default product's contribution, after the chama's contribution amount changed. */
    public SavingsProduct followContributionAmount(long amountMinor, Instant now) {
        return new SavingsProduct(id, chamaId, code, name, kind, ledgerAccountCode, amountMinor, allocationPriority,
                mandatory, countsTowardLoanLimit, refundableOnExit, earnsDividend, defaultProduct, active, createdBy,
                createdAt, now, version);
    }

    /**
     * An inactive product asks for nothing in new periods, takes no payments and opens no
     * accounts. Balances stay where they are.
     */
    public SavingsProduct withActive(boolean value, Instant now) {
        if (!value && defaultProduct) {
            throw new DefaultProductRuleException(name + " is the default product and cannot be deactivated");
        }
        return new SavingsProduct(id, chamaId, code, name, kind, ledgerAccountCode, contributionMinor,
                allocationPriority, mandatory, countsTowardLoanLimit, refundableOnExit, earnsDividend, defaultProduct,
                value, createdBy, createdAt, now, version);
    }

    /** Whether new contribution periods ask members for something towards this product. */
    public boolean asksForContributions() {
        return active && contributionMinor > 0;
    }

    public static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw new InvalidProductException("The code must be 2 to 10 letters or digits starting with a letter, e.g. SHR");
        }
        return normalized;
    }

    private static void validate(Terms terms) {
        if (terms == null) {
            throw new InvalidProductException("The product's terms are required");
        }
        if (terms.name() == null || terms.name().isBlank() || terms.name().trim().length() > MAX_NAME_LENGTH) {
            throw new InvalidProductException("A name is required (at most " + MAX_NAME_LENGTH + " characters)");
        }
        if (terms.contributionMinor() < 0) {
            throw new InvalidProductException("The contribution cannot be negative; use 0 for top-ups only");
        }
        if (terms.allocationPriority() < 1 || terms.allocationPriority() > 99) {
            throw new InvalidProductException("The allocation priority must be between 1 and 99");
        }
    }

    private static boolean orDefault(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
