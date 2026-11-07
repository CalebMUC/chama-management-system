package ke.chama.modules.loan.domain.model;

import ke.chama.modules.chama.api.LoanRules;
import ke.chama.modules.loan.domain.exception.LoanExceptions.DefaultLoanProductRuleException;
import ke.chama.modules.loan.domain.exception.LoanExceptions.InvalidLoanProductException;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A kind of loan the chama offers, e.g. "Normal loan" (12%, up to 3× savings, 12 months) or
 * "Emergency loan" (5%, up to 1× savings, 3 months, no guarantors). A member may have one loan in
 * progress per product. Every loan records the terms in force when it was applied for, so later
 * changes to a product never alter existing loans.
 * <p>
 * Every chama has a default product ("Normal loan") whose terms are the chama's loan settings and
 * follow every change to them; it is always offered.
 *
 * @param maxMultiplier a loan may be at most this many times the member's loan-backing savings
 */
public record LoanProduct(UUID id,
                          UUID chamaId,
                          String code,
                          String name,
                          int interestBasisPoints,
                          int maxTermMonths,
                          int maxMultiplier,
                          int minMembershipMonths,
                          long guarantorThresholdMinor,
                          int guarantorsRequired,
                          boolean defaultProduct,
                          boolean active,
                          UUID createdBy,
                          Instant createdAt,
                          Instant updatedAt,
                          long version) {

    public static final String DEFAULT_CODE = "NORMAL";
    static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");
    static final int MAX_NAME_LENGTH = 60;

    public LoanProduct {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chamaId, "chamaId");
    }

    /** What an official sets on a loan product. */
    public record Terms(String name,
                        int interestBasisPoints,
                        int maxTermMonths,
                        int maxMultiplier,
                        int minMembershipMonths,
                        long guarantorThresholdMinor,
                        int guarantorsRequired) {
    }

    public static LoanProduct create(UUID chamaId, String code, Terms terms, UUID createdBy, Instant now) {
        String normalized = normalizeCode(code);
        validate(terms);
        return new LoanProduct(UUID.randomUUID(), chamaId, normalized, terms.name().trim(), terms.interestBasisPoints(),
                terms.maxTermMonths(), terms.maxMultiplier(), terms.minMembershipMonths(),
                terms.guarantorThresholdMinor(), terms.guarantorsRequired(), false, true, createdBy, now, now, 0);
    }

    /** The product every chama starts with, on the chama's loan settings. */
    public static LoanProduct defaultFrom(UUID chamaId, LoanRules rules, Instant now) {
        return new LoanProduct(UUID.randomUUID(), chamaId, DEFAULT_CODE, "Normal loan", rules.interestBasisPoints(),
                Loan.MAX_TERM_MONTHS, rules.maxMultiplier(), rules.minMembershipMonths(),
                rules.guarantorThresholdMinor(), rules.guarantorsRequired(), true, true, null, now, now, 0);
    }

    /** New terms for loans applied for from now on. The default product can only be renamed here. */
    public LoanProduct update(Terms terms, Instant now) {
        validate(terms);
        if (defaultProduct && (terms.interestBasisPoints() != interestBasisPoints
                || terms.maxMultiplier() != maxMultiplier || terms.minMembershipMonths() != minMembershipMonths
                || terms.guarantorThresholdMinor() != guarantorThresholdMinor
                || terms.guarantorsRequired() != guarantorsRequired || terms.maxTermMonths() != maxTermMonths)) {
            throw new DefaultLoanProductRuleException("The terms of " + name
                    + " are the chama's loan settings; change them in the chama's settings");
        }
        return new LoanProduct(id, chamaId, code, terms.name().trim(), terms.interestBasisPoints(), terms.maxTermMonths(),
                terms.maxMultiplier(), terms.minMembershipMonths(), terms.guarantorThresholdMinor(),
                terms.guarantorsRequired(), defaultProduct, active, createdBy, createdAt, now, version);
    }

    /** The default product's terms, after the chama's loan settings changed. */
    public LoanProduct followSettings(LoanRules rules, Instant now) {
        return new LoanProduct(id, chamaId, code, name, rules.interestBasisPoints(), maxTermMonths, rules.maxMultiplier(),
                rules.minMembershipMonths(), rules.guarantorThresholdMinor(), rules.guarantorsRequired(),
                defaultProduct, active, createdBy, createdAt, now, version);
    }

    public boolean followsSettings(LoanRules rules) {
        return interestBasisPoints == rules.interestBasisPoints() && maxMultiplier == rules.maxMultiplier()
                && minMembershipMonths == rules.minMembershipMonths()
                && guarantorThresholdMinor == rules.guarantorThresholdMinor()
                && guarantorsRequired == rules.guarantorsRequired();
    }

    /** An inactive product takes no new applications; loans already taken carry on. */
    public LoanProduct withActive(boolean value, Instant now) {
        if (!value && defaultProduct) {
            throw new DefaultLoanProductRuleException(name + " is the default loan product and cannot be deactivated");
        }
        return new LoanProduct(id, chamaId, code, name, interestBasisPoints, maxTermMonths, maxMultiplier,
                minMembershipMonths, guarantorThresholdMinor, guarantorsRequired, defaultProduct, value, createdBy,
                createdAt, now, version);
    }

    /** This product's terms with the chama's currency and approval quorum, in the shape eligibility checks use. */
    public LoanRules rules(String currency, int approvalQuorum) {
        return new LoanRules(currency, maxMultiplier, interestBasisPoints, minMembershipMonths, guarantorThresholdMinor,
                guarantorsRequired, approvalQuorum);
    }

    public static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw new InvalidLoanProductException("The code must be 2 to 10 letters or digits starting with a letter, e.g. EMERG");
        }
        return normalized;
    }

    private static void validate(Terms t) {
        if (t == null) {
            throw new InvalidLoanProductException("The product's terms are required");
        }
        if (t.name() == null || t.name().isBlank() || t.name().trim().length() > MAX_NAME_LENGTH) {
            throw new InvalidLoanProductException("A name is required (at most " + MAX_NAME_LENGTH + " characters)");
        }
        require(t.interestBasisPoints() >= 0 && t.interestBasisPoints() <= 10_000, "Interest must be between 0% and 100%");
        require(t.maxTermMonths() >= 1 && t.maxTermMonths() <= Loan.MAX_TERM_MONTHS,
                "The longest term must be between 1 and " + Loan.MAX_TERM_MONTHS + " months");
        require(t.maxMultiplier() >= 1 && t.maxMultiplier() <= 10, "The multiplier must be between 1 and 10");
        require(t.minMembershipMonths() >= 0 && t.minMembershipMonths() <= 60,
                "Months of membership must be between 0 and 60");
        require(t.guarantorThresholdMinor() >= 0, "The guarantor threshold must not be negative");
        require(t.guarantorsRequired() >= 0 && t.guarantorsRequired() <= 5, "Guarantors required must be between 0 and 5");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidLoanProductException(message);
        }
    }
}
