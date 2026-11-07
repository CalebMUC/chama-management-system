package ke.chama.modules.dataimport.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A checked opening-balance import: what will be created, and every problem found. It can be
 * imported only when no problem is an error. Amounts are minor units.
 */
public record OpeningBalancePlan(LocalDate asOf,
                                 List<NewMember> newMembers,
                                 List<MemberBalance> balances,
                                 List<PlannedLoan> loans,
                                 long cashMinor,
                                 long bankMinor,
                                 long mobileMoneyMinor,
                                 List<PlannedInvestment> investments,
                                 List<Problem> problems) {

    public OpeningBalancePlan {
        newMembers = List.copyOf(newMembers);
        balances = List.copyOf(balances);
        loans = List.copyOf(loans);
        investments = List.copyOf(investments);
        problems = List.copyOf(problems);
    }

    /** Someone who gets an account and a membership. {@code phone} is normalised (+2547...). */
    public record NewMember(int row, String email, String firstName, String lastName, String phone, LocalDate joinedOn,
                            Set<String> roles) {
    }

    /** @param existingUserId set for someone already a member; null for a new member (matched by email) */
    public record MemberBalance(String email, UUID existingUserId, long savingsMinor, long penaltiesMinor) {
    }

    public record PlannedLoan(String memberEmail, long principalMinor, long interestMinor, int termMonths,
                              LocalDate disbursedOn, long repaidMinor, String purpose) {

        public long outstandingMinor() {
            return principalMinor + interestMinor - repaidMinor;
        }
    }

    public record PlannedInvestment(String name, String type, String institution, LocalDate investedOn, long amountMinor,
                                    Long currentValueMinor, Integer expectedReturnBasisPoints) {
    }

    public boolean importable() {
        return problems.stream().noneMatch(Problem::blocking);
    }

    public long savingsMinor() {
        return balances.stream().mapToLong(MemberBalance::savingsMinor).sum();
    }

    public long penaltiesMinor() {
        return balances.stream().mapToLong(MemberBalance::penaltiesMinor).sum();
    }

    public long loansOutstandingMinor() {
        return loans.stream().mapToLong(PlannedLoan::outstandingMinor).sum();
    }

    public long investmentsMinor() {
        return investments.stream().mapToLong(PlannedInvestment::amountMinor).sum();
    }

    public long accountsMinor() {
        return cashMinor + bankMinor + mobileMoneyMinor;
    }

    /**
     * What the chama owns (cash, investments, what members owe it) less what it owes members
     * (their savings): the surplus brought forward. Negative when savings exceed what the chama holds.
     */
    public long retainedSurplusMinor() {
        return accountsMinor() + investmentsMinor() + loansOutstandingMinor() + penaltiesMinor() - savingsMinor();
    }
}
