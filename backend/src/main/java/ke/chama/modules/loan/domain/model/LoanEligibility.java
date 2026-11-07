package ke.chama.modules.loan.domain.model;

import ke.chama.modules.chama.api.LoanRules;
import ke.chama.modules.loan.domain.exception.LoanExceptions.NotEligibleException;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a member may borrow under one loan product, and how much: at most {@code maxMultiplier}
 * times their loan-backing savings, less what they already owe or have pending on loans of other
 * products, after {@code minMembershipMonths} of membership, while active, and with no other loan
 * of the same product open (a member clears one before taking the next).
 *
 * @param savingsMinor    savings in the products that count toward the loan limit
 * @param otherLoansMinor still owed on active loans, or applied for on undisbursed ones, of other products
 * @param reasons         why the member cannot borrow; empty when they can
 */
public record LoanEligibility(LoanRules rules,
                              String productName,
                              int maxTermMonths,
                              long savingsMinor,
                              long otherLoansMinor,
                              long maxLoanMinor,
                              int membershipMonths,
                              List<String> reasons) {

    /** A single loan product with the longest term allowed and no other loans. */
    public static LoanEligibility assess(LoanRules rules, boolean activeMember, long savingsMinor, LocalDate joinedOn,
                                         LocalDate today, boolean hasOpenLoan) {
        return assess(rules, "loan", Loan.MAX_TERM_MONTHS, activeMember, savingsMinor, 0, joinedOn, today, hasOpenLoan);
    }

    public static LoanEligibility assess(LoanRules rules, String productName, int maxTermMonths, boolean activeMember,
                                         long savingsMinor, long otherLoansMinor, LocalDate joinedOn, LocalDate today,
                                         boolean hasOpenLoanOfProduct) {
        int months = joinedOn == null ? 0 : (int) Math.max(Period.between(joinedOn, today).toTotalMonths(), 0);
        long max = Math.max(Math.max(savingsMinor, 0) * rules.maxMultiplier() - Math.max(otherLoansMinor, 0), 0);
        List<String> reasons = new ArrayList<>();
        if (!activeMember) {
            reasons.add("Only active members can borrow.");
        }
        if (months < rules.minMembershipMonths()) {
            reasons.add("Members can borrow after " + rules.minMembershipMonths() + " months of membership; you have "
                    + months + ".");
        }
        if (savingsMinor <= 0) {
            reasons.add("You need savings with the chama before you can borrow.");
        } else if (max == 0) {
            reasons.add("Your other loans (" + Loan.formatted(otherLoansMinor, rules.currency()) + ") already use up "
                    + rules.maxMultiplier() + "× your savings.");
        }
        if (hasOpenLoanOfProduct) {
            reasons.add("You already have " + withArticle(productName) + " in progress. Repay or cancel it before applying again.");
        }
        return new LoanEligibility(rules, productName, maxTermMonths, savingsMinor, otherLoansMinor, max, months,
                List.copyOf(reasons));
    }

    public boolean eligible() {
        return reasons.isEmpty();
    }

    /** Throws unless the member may borrow {@code principalMinor}. */
    public void requireAllows(long principalMinor) {
        requireAllows(principalMinor, 1);
    }

    /** Throws unless the member may borrow {@code principalMinor} over {@code termMonths}. */
    public void requireAllows(long principalMinor, int termMonths) {
        List<String> all = new ArrayList<>(reasons);
        if (savingsMinor > 0 && maxLoanMinor > 0 && principalMinor > maxLoanMinor) {
            String limit = rules.maxMultiplier() + "× your savings of " + Loan.formatted(savingsMinor, rules.currency());
            if (otherLoansMinor > 0) {
                limit += ", less " + Loan.formatted(otherLoansMinor, rules.currency()) + " on your other loans";
            }
            all.add("The most you can borrow is " + Loan.formatted(maxLoanMinor, rules.currency()) + " (" + limit + ").");
        }
        if (termMonths > maxTermMonths) {
            all.add("The longest term for " + withArticle(productName) + " is " + maxTermMonths + " months.");
        }
        if (!all.isEmpty()) {
            throw new NotEligibleException(all);
        }
    }

    /** "an emergency loan", "a normal loan". */
    private static String withArticle(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return ("aeiou".indexOf(lower.isEmpty() ? 'x' : lower.charAt(0)) >= 0 ? "an " : "a ") + lower;
    }
}
