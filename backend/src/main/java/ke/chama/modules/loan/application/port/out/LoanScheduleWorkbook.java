package ke.chama.modules.loan.application.port.out;

import java.time.LocalDate;
import java.util.List;

/** Writes the loan schedules workbook (.xlsx). Amounts are minor units. */
public interface LoanScheduleWorkbook {

    /**
     * @param status PAID, PARTLY_PAID, DUE or OVERDUE
     */
    record InstalmentLine(int number, LocalDate dueDate, long amountMinor, long paidMinor, String status) {
    }

    record LoanLine(Integer memberNumber, String memberName, String product, String purpose, LocalDate disbursedOn,
                    int termMonths, long principalMinor, long interestMinor, long totalMinor, long repaidMinor,
                    long outstandingMinor, long arrearsMinor, int instalmentsOverdue, int daysInArrears,
                    LocalDate nextDueDate, long nextDueMinor, LocalDate maturityDate, List<InstalmentLine> schedule) {
    }

    byte[] write(String chamaName, String currency, LocalDate asOf, List<LoanLine> loans);
}
