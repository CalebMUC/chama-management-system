package ke.chama.modules.dataimport.domain.model;

import ke.chama.modules.dataimport.domain.model.OpeningBalancePlan.MemberBalance;
import ke.chama.modules.dataimport.domain.model.OpeningBalancePlan.NewMember;
import ke.chama.modules.dataimport.domain.model.OpeningBalancePlan.PlannedInvestment;
import ke.chama.modules.dataimport.domain.model.OpeningBalancePlan.PlannedLoan;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.AccountRow;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.InvestmentRow;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.LoanRow;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.MemberRow;
import ke.chama.shared.domain.PhoneNumber;
import ke.chama.shared.domain.exception.DomainException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.ACCOUNTS;
import static ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.INVESTMENTS;
import static ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.LOANS;
import static ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.MEMBERS;

/**
 * Checks an opening-balance workbook against the chama as it is now, and turns it into a plan.
 * Reports every problem it finds, not just the first, so the whole file can be fixed at once.
 */
public final class OpeningBalanceCheck {

    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_TEXT_LENGTH = 300;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * The chama as it is now.
     *
     * @param seatedMembersByEmail active and suspended members, by lower-case email
     * @param otherAccountEmails   lower-case emails with an account here but no seat (e.g. a pending application)
     * @param existingSavingsMinor savings already recorded for existing members, by user id
     * @param earliestContributionDue the first contribution period's due date in the system, if any
     */
    public record Chama(LocalDate today,
                        Map<String, UUID> seatedMembersByEmail,
                        Set<String> otherAccountEmails,
                        Map<UUID, Long> existingSavingsMinor,
                        Set<UUID> borrowersWithOpenLoans,
                        Set<String> roleNames,
                        Set<String> investmentTypes,
                        int maxLoanTermMonths,
                        int maxMembers,
                        Optional<LocalDate> earliestContributionDue,
                        String currency) {
    }

    private OpeningBalanceCheck() {
    }

    public static OpeningBalancePlan check(OpeningBalanceSheets sheets, LocalDate asOf, Chama chama) {
        List<Problem> problems = new ArrayList<>(sheets.problems());
        if (asOf == null) {
            problems.add(Problem.error(null, null, null, "A cut-over date is required"));
            return new OpeningBalancePlan(null, List.of(), List.of(), List.of(), 0, 0, 0, List.of(), problems);
        }
        if (asOf.isAfter(chama.today())) {
            problems.add(Problem.error(null, null, null, "The cut-over date cannot be in the future"));
        }
        chama.earliestContributionDue().filter(due -> !due.isAfter(asOf)).ifPresent(due -> problems.add(Problem.warning(
                null, null, null, "The chama's first contribution period in the system was due on " + due
                        + ", on or before the cut-over date. Members will owe it in the system as well; if their "
                        + "savings in this file already include it, choose a cut-over date before " + due + ".")));

        List<NewMember> newMembers = new ArrayList<>();
        List<MemberBalance> balances = new ArrayList<>();
        Set<String> memberEmails = new HashSet<>();
        checkMembers(sheets.members(), asOf, chama, problems, newMembers, balances, memberEmails);

        List<PlannedLoan> loans = checkLoans(sheets.loans(), asOf, chama, memberEmails, problems);
        long[] accounts = checkAccounts(sheets.accounts(), problems);
        List<PlannedInvestment> investments = checkInvestments(sheets.investments(), asOf, chama, problems);

        int seatedAfter = chama.seatedMembersByEmail().size() + newMembers.size();
        if (seatedAfter > chama.maxMembers()) {
            problems.add(Problem.error(MEMBERS, null, null, "The chama's licence allows " + chama.maxMembers()
                    + " members; importing " + newMembers.size() + " new members would make " + seatedAfter
                    + ". Ask the platform to raise the limit first."));
        }
        if (balances.isEmpty() && loans.isEmpty() && investments.isEmpty() && accounts[0] + accounts[1] + accounts[2] == 0) {
            problems.add(Problem.error(null, null, null, "The file has nothing to import"));
        }
        return new OpeningBalancePlan(asOf, newMembers, balances, loans, accounts[0], accounts[1], accounts[2],
                investments, problems);
    }

    // ---- members --------------------------------------------------------------------------------

    private static void checkMembers(List<MemberRow> rows, LocalDate asOf, Chama chama, List<Problem> problems,
                                     List<NewMember> newMembers, List<MemberBalance> balances, Set<String> seen) {
        for (MemberRow r : rows) {
            String email = r.email() == null ? null : r.email().trim().toLowerCase(Locale.ROOT);
            if (email == null || email.isEmpty()) {
                problems.add(Problem.error(MEMBERS, r.row(), "Email", "An email is required: members log in with it"));
                continue;
            }
            if (!EMAIL.matcher(email).matches()) {
                problems.add(Problem.error(MEMBERS, r.row(), "Email", email + " is not a valid email address"));
                continue;
            }
            if (!seen.add(email)) {
                problems.add(Problem.error(MEMBERS, r.row(), "Email", email + " appears more than once"));
                continue;
            }
            long savings = amount(r.savingsMinor(), MEMBERS, r.row(), "Savings (KES)", problems);
            long penalties = amount(r.penaltiesMinor(), MEMBERS, r.row(), "Penalties owed (KES)", problems);
            Map<String, Long> otherSavings = new LinkedHashMap<>();
            r.otherSavingsMinor().forEach((column, value) -> {
                long minor = amount(value, MEMBERS, r.row(), column, problems);
                if (minor > 0) {
                    otherSavings.put(column, minor);
                }
            });

            UUID existing = chama.seatedMembersByEmail().get(email);
            if (existing != null) {
                if (!r.roles().isEmpty()) {
                    problems.add(Problem.warning(MEMBERS, r.row(), "Roles", email
                            + " is already a member: their roles are not changed by the import"));
                }
                long already = chama.existingSavingsMinor().getOrDefault(existing, 0L);
                if (already > 0 && (savings > 0 || !otherSavings.isEmpty())) {
                    problems.add(Problem.warning(MEMBERS, r.row(), "Savings (KES)", email + " already has savings of "
                            + Amounts.format(already, chama.currency()) + " in the system; the imported amount is added to it"));
                }
                balances.add(new MemberBalance(email, existing, savings, penalties, otherSavings));
                continue;
            }
            if (chama.otherAccountEmails().contains(email)) {
                problems.add(Problem.error(MEMBERS, r.row(), "Email", email
                        + " already has an account in this chama but is not a member (e.g. a pending application). "
                        + "Approve or reject them first, then import again."));
                continue;
            }
            int before = problems.size();
            String first = name(r.firstName(), MEMBERS, r.row(), "First name", problems);
            String last = name(r.lastName(), MEMBERS, r.row(), "Last name", problems);
            String phone = null;
            try {
                phone = PhoneNumber.of(r.phone()).value();
            } catch (DomainException e) {
                problems.add(Problem.error(MEMBERS, r.row(), "Phone", e.getMessage()));
            }
            if (r.joinedOn() == null) {
                problems.add(Problem.error(MEMBERS, r.row(), "Joined on", "The date the member joined is required"));
            } else if (r.joinedOn().isAfter(asOf)) {
                problems.add(Problem.error(MEMBERS, r.row(), "Joined on", "Must be on or before the cut-over date " + asOf));
            }
            Set<String> roles = new LinkedHashSet<>();
            for (String role : r.roles()) {
                String normalized = role.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
                if (!chama.roleNames().contains(normalized)) {
                    problems.add(Problem.error(MEMBERS, r.row(), "Roles", "Unknown role " + role + "; use "
                            + String.join(", ", chama.roleNames().stream().sorted().toList())));
                } else {
                    roles.add(normalized);
                }
            }
            if (problems.size() == before) {
                newMembers.add(new NewMember(r.row(), email, first, last, phone, r.joinedOn(), roles));
            }
            balances.add(new MemberBalance(email, null, savings, penalties, otherSavings));
        }
    }

    // ---- loans ----------------------------------------------------------------------------------

    private static List<PlannedLoan> checkLoans(List<LoanRow> rows, LocalDate asOf, Chama chama, Set<String> memberEmails,
                                                List<Problem> problems) {
        List<PlannedLoan> loans = new ArrayList<>();
        Set<String> borrowers = new HashSet<>();
        for (LoanRow r : rows) {
            int before = problems.size();
            String email = r.memberEmail() == null ? "" : r.memberEmail().trim().toLowerCase(Locale.ROOT);
            UUID existing = chama.seatedMembersByEmail().get(email);
            if (email.isEmpty()) {
                problems.add(Problem.error(LOANS, r.row(), "Member email", "The borrower's email is required"));
            } else if (!memberEmails.contains(email) && existing == null) {
                problems.add(Problem.error(LOANS, r.row(), "Member email", email
                        + " is not on the Members sheet and is not a member already"));
            } else if (!borrowers.add(email)) {
                problems.add(Problem.error(LOANS, r.row(), "Member email", email
                        + " has more than one loan; a member has one loan at a time. Combine them into one row."));
            } else if (existing != null && chama.borrowersWithOpenLoans().contains(existing)) {
                problems.add(Problem.error(LOANS, r.row(), "Member email", email + " already has a loan in the system"));
            }
            Long principal = r.principalMinor();
            if (principal == null || principal <= 0) {
                problems.add(Problem.error(LOANS, r.row(), "Principal (KES)", "The amount borrowed must be greater than zero"));
            }
            long interest = amount(r.interestMinor(), LOANS, r.row(), "Interest (KES)", problems);
            long repaid = amount(r.repaidMinor(), LOANS, r.row(), "Repaid so far (KES)", problems);
            Integer term = r.termMonths();
            if (term == null || term < 1 || term > chama.maxLoanTermMonths()) {
                problems.add(Problem.error(LOANS, r.row(), "Term (months)", "Must be between 1 and "
                        + chama.maxLoanTermMonths()));
            }
            if (r.disbursedOn() == null) {
                problems.add(Problem.error(LOANS, r.row(), "Disbursed on", "The date the loan was paid out is required"));
            } else if (r.disbursedOn().isAfter(asOf)) {
                problems.add(Problem.error(LOANS, r.row(), "Disbursed on", "Must be on or before the cut-over date " + asOf));
            }
            if (principal != null && principal > 0 && repaid >= principal + interest) {
                problems.add(Problem.error(LOANS, r.row(), "Repaid so far (KES)",
                        "The loan is fully repaid; leave fully repaid loans out of the file"));
            }
            String purpose = r.purpose() == null || r.purpose().isBlank() ? null : r.purpose().trim();
            if (purpose != null && purpose.length() > MAX_TEXT_LENGTH) {
                problems.add(Problem.error(LOANS, r.row(), "Purpose", "At most " + MAX_TEXT_LENGTH + " characters"));
            }
            if (problems.size() == before) {
                loans.add(new PlannedLoan(email, principal, interest, term, r.disbursedOn(), repaid, purpose));
            }
        }
        return loans;
    }

    // ---- accounts -------------------------------------------------------------------------------

    /** Returns {cash, bank, M-Pesa}. */
    private static long[] checkAccounts(List<AccountRow> rows, List<Problem> problems) {
        long[] balances = new long[3];
        boolean[] seen = new boolean[3];
        for (AccountRow r : rows) {
            int index = accountIndex(r.account());
            if (index < 0) {
                problems.add(Problem.error(ACCOUNTS, r.row(), "Account", "Unknown account " + r.account()
                        + "; use Cash on hand, Bank account or M-Pesa"));
                continue;
            }
            if (seen[index]) {
                problems.add(Problem.error(ACCOUNTS, r.row(), "Account", r.account() + " appears more than once"));
                continue;
            }
            seen[index] = true;
            balances[index] = amount(r.balanceMinor(), ACCOUNTS, r.row(), "Balance (KES)", problems);
        }
        return balances;
    }

    static int accountIndex(String account) {
        String a = account == null ? "" : account.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (a) {
            case "cash", "cashonhand" -> 0;
            case "bank", "bankaccount" -> 1;
            case "mpesa", "mobilemoney", "mobilemoneympesa" -> 2;
            default -> -1;
        };
    }

    // ---- investments ----------------------------------------------------------------------------

    private static List<PlannedInvestment> checkInvestments(List<InvestmentRow> rows, LocalDate asOf, Chama chama,
                                                            List<Problem> problems) {
        List<PlannedInvestment> investments = new ArrayList<>();
        for (InvestmentRow r : rows) {
            int before = problems.size();
            String name = r.name() == null ? "" : r.name().trim();
            if (name.isEmpty() || name.length() > 120) {
                problems.add(Problem.error(INVESTMENTS, r.row(), "Name", "A name of at most 120 characters is required"));
            }
            String type = r.type() == null ? "" : r.type().trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            if (!chama.investmentTypes().contains(type)) {
                problems.add(Problem.error(INVESTMENTS, r.row(), "Type", "Unknown type " + r.type() + "; use "
                        + String.join(", ", chama.investmentTypes().stream().sorted().toList())));
            }
            if (r.investedOn() == null) {
                problems.add(Problem.error(INVESTMENTS, r.row(), "Invested on", "The date is required"));
            } else if (r.investedOn().isAfter(asOf)) {
                problems.add(Problem.error(INVESTMENTS, r.row(), "Invested on", "Must be on or before the cut-over date " + asOf));
            }
            if (r.amountMinor() == null || r.amountMinor() <= 0) {
                problems.add(Problem.error(INVESTMENTS, r.row(), "Amount invested (KES)", "Must be greater than zero"));
            }
            if (r.currentValueMinor() != null && r.currentValueMinor() < 0) {
                problems.add(Problem.error(INVESTMENTS, r.row(), "Current value (KES)", "Cannot be negative"));
            }
            Integer expected = r.expectedReturnBasisPoints();
            if (expected != null && (expected < 0 || expected > 100_000)) {
                problems.add(Problem.error(INVESTMENTS, r.row(), "Expected return (%)", "Must be between 0 and 1000"));
            }
            String institution = r.institution() == null || r.institution().isBlank() ? null : r.institution().trim();
            if (problems.size() == before) {
                investments.add(new PlannedInvestment(name, type, institution, r.investedOn(), r.amountMinor(),
                        r.currentValueMinor(), expected));
            }
        }
        return investments;
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** An optional amount: blank is zero; negative is an error. */
    private static long amount(Long value, String sheet, int row, String column, List<Problem> problems) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            problems.add(Problem.error(sheet, row, column, "Cannot be negative"));
            return 0;
        }
        return value;
    }

    private static String name(String value, String sheet, int row, String column, List<Problem> problems) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.length() > MAX_NAME_LENGTH) {
            problems.add(Problem.error(sheet, row, column, "Required, at most " + MAX_NAME_LENGTH + " characters"));
        }
        return v;
    }

    /** Groups problems by sheet, for summaries. */
    public static Map<String, Long> errorsBySheet(List<Problem> problems) {
        Map<String, Long> counts = new HashMap<>();
        problems.stream().filter(Problem::blocking)
                .forEach(p -> counts.merge(p.sheet() == null ? "File" : p.sheet(), 1L, Long::sum));
        return counts;
    }
}
