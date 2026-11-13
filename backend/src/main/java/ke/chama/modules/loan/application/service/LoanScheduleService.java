package ke.chama.modules.loan.application.service;

import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.loan.application.port.in.LoanSchedules;
import ke.chama.modules.loan.application.port.out.LoanProductRepository;
import ke.chama.modules.loan.application.port.out.LoanRepository;
import ke.chama.modules.loan.application.port.out.LoanScheduleWorkbook;
import ke.chama.modules.loan.application.port.out.LoanScheduleWorkbook.InstalmentLine;
import ke.chama.modules.loan.application.port.out.LoanScheduleWorkbook.LoanLine;
import ke.chama.modules.loan.domain.model.Loan;
import ke.chama.modules.loan.domain.model.Loan.Instalment;
import ke.chama.modules.loan.domain.model.LoanProduct;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The schedules of every loan still being repaid, borrowers by member number. */
@Service
@Transactional(readOnly = true)
class LoanScheduleService implements LoanSchedules {

    private final LoanRepository loans;
    private final LoanProductRepository products;
    private final MemberDirectory members;
    private final ChamaDirectory chamas;
    private final LoanScheduleWorkbook workbook;
    private final Clock clock;
    private final ZoneId zone;

    LoanScheduleService(LoanRepository loans, LoanProductRepository products, MemberDirectory members,
                        ChamaDirectory chamas, LoanScheduleWorkbook workbook, Clock clock,
                        @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.loans = loans;
        this.products = products;
        this.members = members;
        this.chamas = chamas;
        this.workbook = workbook;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    @Override
    public byte[] export(UUID chamaId) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        ChamaSummary chama = chamas.find(chamaId).orElseThrow();
        Map<UUID, MemberSummary> seated = members.seatedMembers(chamaId).stream()
                .collect(Collectors.toMap(MemberSummary::userId, Function.identity()));
        Map<UUID, String> productNames = products.products(chamaId).stream()
                .collect(Collectors.toMap(LoanProduct::id, LoanProduct::name));
        List<LoanLine> lines = loans.allLoans(chamaId).stream()
                .filter(l -> l.status() == Loan.Status.ACTIVE)
                .map(l -> line(l, seated.get(l.borrowerUserId()), productNames.get(l.productId()), today))
                .sorted(Comparator.comparing((LoanLine l) -> l.memberNumber() == null ? Integer.MAX_VALUE : l.memberNumber())
                        .thenComparing(LoanLine::disbursedOn))
                .toList();
        return workbook.write(chama.name(), chama.currency(), today, lines);
    }

    private static LoanLine line(Loan loan, MemberSummary member, String product, LocalDate today) {
        List<Instalment> schedule = loan.schedule(today);
        Optional<Instalment> next = loan.nextInstalment(today);
        String name = member == null ? "(no longer a member)" : member.fullName().trim();
        return new LoanLine(member == null ? null : member.memberNumber(), name, product, loan.purpose(),
                loan.disbursedOn(), loan.termMonths(), loan.principalMinor(), loan.interestMinor(), loan.totalDueMinor(),
                loan.repaidMinor(), loan.outstandingMinor(), loan.arrearsMinor(today),
                (int) schedule.stream().filter(Instalment::overdue).count(), loan.daysInArrears(today),
                next.map(Instalment::dueDate).orElse(null), next.map(Instalment::unpaidMinor).orElse(0L),
                loan.maturityDate(), schedule.stream().map(i -> new InstalmentLine(i.number(), i.dueDate(),
                i.amountMinor(), i.paidMinor(), i.status())).toList());
    }
}
