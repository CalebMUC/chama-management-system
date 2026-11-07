package ke.chama.modules.dataimport.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * What was read from an opening-balance workbook, before checking. Amounts are minor units; a
 * value that could not be read is null, and the reader has already reported a {@link Problem}
 * for it. Row numbers are as Excel shows them (the header is row 1).
 */
public record OpeningBalanceSheets(List<MemberRow> members,
                                   List<LoanRow> loans,
                                   List<AccountRow> accounts,
                                   List<InvestmentRow> investments,
                                   List<Problem> problems) {

    public static final String MEMBERS = "Members";
    public static final String LOANS = "Loans";
    public static final String ACCOUNTS = "Accounts";
    public static final String INVESTMENTS = "Investments";

    public OpeningBalanceSheets {
        members = List.copyOf(members);
        loans = List.copyOf(loans);
        accounts = List.copyOf(accounts);
        investments = List.copyOf(investments);
        problems = List.copyOf(problems);
    }

    /** @param roles role names as typed, e.g. "Treasurer"; empty means an ordinary member */
    public record MemberRow(int row, String email, String firstName, String lastName, String phone, LocalDate joinedOn,
                            Set<String> roles, Long savingsMinor, Long penaltiesMinor) {
    }

    public record LoanRow(int row, String memberEmail, Long principalMinor, Long interestMinor, Integer termMonths,
                          LocalDate disbursedOn, Long repaidMinor, String purpose) {
    }

    /** @param account as typed, e.g. "M-Pesa" */
    public record AccountRow(int row, String account, Long balanceMinor) {
    }

    /** @param expectedReturnBasisPoints from a percentage, e.g. 12.5% = 1250 */
    public record InvestmentRow(int row, String name, String type, String institution, LocalDate investedOn,
                                Long amountMinor, Long currentValueMinor, Integer expectedReturnBasisPoints) {
    }
}
