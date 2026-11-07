package ke.chama.modules.dataimport.application.service;

import ke.chama.modules.account.api.MemberAccounts;
import ke.chama.modules.account.api.SavingsProductSummary;
import ke.chama.modules.account.api.SavingsProducts;
import ke.chama.modules.audit.api.AuditEntry;
import ke.chama.modules.audit.api.AuditTrail;
import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserProvisioning;
import ke.chama.modules.auth.api.UserProvisioning.ImportedAccount;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.chama.api.ChamaDirectory;
import ke.chama.modules.chama.api.ChamaSummary;
import ke.chama.modules.chama.api.LicenseSummary;
import ke.chama.modules.contribution.api.ContributionCalendar;
import ke.chama.modules.dataimport.application.port.in.OpeningBalanceImports;
import ke.chama.modules.dataimport.application.port.out.ImportRecords;
import ke.chama.modules.dataimport.application.port.out.SpreadsheetFormat;
import ke.chama.modules.dataimport.domain.exception.ImportExceptions.AlreadyImportedException;
import ke.chama.modules.dataimport.domain.exception.ImportExceptions.ImportHasProblemsException;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceCheck;
import ke.chama.modules.dataimport.domain.model.OpeningBalancePlan;
import ke.chama.modules.dataimport.domain.model.OpeningBalancePlan.MemberBalance;
import ke.chama.modules.dataimport.domain.model.OpeningBalancePlan.NewMember;
import ke.chama.modules.dataimport.domain.model.OpeningBalancePlan.PlannedInvestment;
import ke.chama.modules.dataimport.domain.model.OpeningBalancePlan.PlannedLoan;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets;
import ke.chama.modules.dataimport.domain.model.Problem;
import ke.chama.modules.investment.api.InvestmentImport;
import ke.chama.modules.investment.api.InvestmentImport.ImportedInvestment;
import ke.chama.modules.loan.api.LoanImport;
import ke.chama.modules.loan.api.LoanImport.ImportedLoan;
import ke.chama.modules.member.api.MemberDirectory;
import ke.chama.modules.member.api.MemberImport;
import ke.chama.modules.member.api.MemberSummary;
import ke.chama.modules.penalty.api.PenaltyImport;
import ke.chama.modules.transaction.api.EntrySide;
import ke.chama.modules.transaction.api.Ledger;
import ke.chama.modules.transaction.api.LedgerAccountKey;
import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.api.PostingRequest.PostingLine;
import ke.chama.shared.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Brings a chama's opening balances over from its spreadsheets in one transaction:
 * <ol>
 *   <li>accounts and memberships for new members (each gets a welcome email to set a password);</li>
 *   <li>one ledger entry dated the cut-over day: Dr cash, bank, M-Pesa, investments, and each member's
 *       loan and penalties owed; Cr each member's savings; the difference to retained surplus;</li>
 *   <li>the loans, penalties and investments themselves, pointing at that entry, so repayments,
 *       payments and income are recorded normally from then on.</li>
 * </ol>
 */
@Service
@Transactional
public class OpeningBalanceImportService implements OpeningBalanceImports {

    static final String SOURCE = "OPENING_BALANCE";
    static final String AUDIT_TARGET = "IMPORT";

    private final SpreadsheetFormat spreadsheets;
    private final ImportRecords records;
    private final ChamaDirectory chamas;
    private final MemberDirectory members;
    private final MemberImport memberImport;
    private final UserDirectory users;
    private final UserProvisioning provisioning;
    private final LoanImport loanImport;
    private final PenaltyImport penaltyImport;
    private final InvestmentImport investmentImport;
    private final ContributionCalendar contributions;
    private final SavingsProducts savingsProducts;
    private final MemberAccounts memberAccounts;
    private final Ledger ledger;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final ZoneId zone;

    public OpeningBalanceImportService(SpreadsheetFormat spreadsheets, ImportRecords records, ChamaDirectory chamas,
                                       MemberDirectory members, MemberImport memberImport, UserDirectory users,
                                       UserProvisioning provisioning, LoanImport loanImport, PenaltyImport penaltyImport,
                                       InvestmentImport investmentImport, ContributionCalendar contributions,
                                       SavingsProducts savingsProducts, MemberAccounts memberAccounts,
                                       Ledger ledger, AuditTrail auditTrail, Clock clock,
                                       @Value("${chama.time-zone:Africa/Nairobi}") String timeZone) {
        this.savingsProducts = savingsProducts;
        this.memberAccounts = memberAccounts;
        this.spreadsheets = spreadsheets;
        this.records = records;
        this.chamas = chamas;
        this.members = members;
        this.memberImport = memberImport;
        this.users = users;
        this.provisioning = provisioning;
        this.loanImport = loanImport;
        this.penaltyImport = penaltyImport;
        this.investmentImport = investmentImport;
        this.contributions = contributions;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
    }

    @Override
    public byte[] template(UUID chamaId) {
        return spreadsheets.template(memberImport.roleNames(), investmentImport.typeNames(),
                List.copyOf(otherSavingsColumns(chamaId).keySet()));
    }

    @Override
    @Transactional(readOnly = true)
    public PreviewView preview(UUID chamaId, byte[] file, LocalDate asOf) {
        OpeningBalancePlan plan = plan(chamaId, file, asOf);
        List<Problem> problems = new ArrayList<>(plan.problems());
        records.find(chamaId).ifPresent(done -> problems.add(0, Problem.error(null, null, null,
                "Opening balances were already imported (as at " + done.asOf() + ")")));
        long errors = problems.stream().filter(Problem::blocking).count();
        return new PreviewView(errors == 0, asOf, errors, problems.size() - errors, summary(chamaId, plan),
                problems.stream().map(OpeningBalanceImportService::view).toList());
    }

    @Override
    public ImportView importBalances(UUID chamaId, UUID actorUserId, String fileName, byte[] file, LocalDate asOf) {
        Optional<ImportView> done = records.find(chamaId);
        if (done.isPresent()) {
            throw new AlreadyImportedException(done.get().asOf().toString());
        }
        OpeningBalancePlan plan = plan(chamaId, file, asOf);
        if (!plan.importable()) {
            throw new ImportHasProblemsException(plan.problems().stream().filter(Problem::blocking).count());
        }
        ChamaSummary chama = chamas.find(chamaId).orElseThrow();
        UUID importId = UUID.randomUUID();

        // 1. New members, in the order they joined, so member numbers follow seniority.
        Map<String, UUID> userIds = new HashMap<>();
        plan.balances().stream().filter(b -> b.existingUserId() != null)
                .forEach(b -> userIds.put(b.email(), b.existingUserId()));
        members.seatedMembers(chamaId).forEach(m -> userIds.putIfAbsent(lower(m.email()), m.userId()));
        plan.newMembers().stream()
                .sorted(Comparator.comparing(NewMember::joinedOn).thenComparing(NewMember::row))
                .forEach(m -> {
                    UserSummary user = provisioning.importAccount(
                            new ImportedAccount(m.email(), m.phone(), m.firstName(), m.lastName()), chama.name());
                    memberImport.importMember(chamaId, user.id(), m.email(), m.joinedOn(), m.roles(), actorUserId);
                    userIds.put(m.email(), user.id());
                });

        // 2. One opening-balance entry for every amount brought forward, and an account in every
        //    optional savings product a member brings a balance into (mandatory ones open by themselves).
        Map<String, SavingsProductSummary> columns = otherSavingsColumns(chamaId);
        UUID entryId = postOpeningEntry(chamaId, chama.currency(), plan, userIds, columns, importId, actorUserId);
        for (MemberBalance b : plan.balances()) {
            for (String column : b.otherSavingsMinor().keySet()) {
                SavingsProductSummary product = columns.get(column);
                if (!product.mandatory()) {
                    memberAccounts.ensureAccount(chamaId, userIds.get(b.email()), product.id(), plan.asOf(), actorUserId);
                }
            }
        }

        // 3. The records behind the amounts.
        for (PlannedLoan l : plan.loans()) {
            loanImport.importLoan(chamaId, new ImportedLoan(userIds.get(l.memberEmail()), l.principalMinor(),
                    l.interestMinor(), l.termMonths(), l.disbursedOn(), l.repaidMinor(), l.purpose(), chama.currency()),
                    actorUserId, entryId);
        }
        for (MemberBalance b : plan.balances()) {
            if (b.penaltiesMinor() > 0) {
                penaltyImport.importOutstanding(chamaId, userIds.get(b.email()), b.penaltiesMinor(), chama.currency(),
                        plan.asOf(), actorUserId, entryId);
            }
        }
        for (PlannedInvestment i : plan.investments()) {
            investmentImport.importInvestment(chamaId, new ImportedInvestment(i.name(), i.type(), i.institution(),
                    i.investedOn(), i.amountMinor(), i.currentValueMinor(), i.expectedReturnBasisPoints(), plan.asOf(),
                    chama.currency()), actorUserId, entryId);
        }

        SummaryView summary = summary(chamaId, plan);
        ImportView record = new ImportView(importId, plan.asOf(), fileName, actorUserId, clock.instant(), entryId,
                summary, plan.problems().stream().map(OpeningBalanceImportService::view).toList());
        records.insert(chamaId, record);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("asOf", plan.asOf().toString());
        details.put("fileName", fileName == null ? "" : fileName);
        details.put("newMembers", summary.newMembers());
        details.put("savingsMinor", summary.savingsMinor());
        details.put("loansOutstandingMinor", summary.loansOutstandingMinor());
        details.put("penaltiesMinor", summary.penaltiesMinor());
        details.put("accountsMinor", plan.accountsMinor());
        details.put("investmentsMinor", summary.investmentsMinor());
        details.put("retainedSurplusMinor", summary.retainedSurplusMinor());
        auditTrail.record(AuditEntry.of(chamaId, actorUserId, "OPENING_BALANCES_IMPORTED", AUDIT_TARGET, importId)
                .with(details));
        return record;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ImportView> imported(UUID chamaId) {
        return records.find(chamaId);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private OpeningBalancePlan plan(UUID chamaId, byte[] file, LocalDate asOf) {
        ChamaSummary chama = chamas.find(chamaId).orElseThrow();
        int digits = Math.max(Currency.getInstance(chama.currency()).getDefaultFractionDigits(), 0);
        OpeningBalanceSheets sheets = spreadsheets.read(file, digits, List.copyOf(otherSavingsColumns(chamaId).keySet()));

        Map<String, UUID> seated = new HashMap<>();
        for (MemberSummary m : members.seatedMembers(chamaId)) {
            if (m.email() != null) {
                seated.put(lower(m.email()), m.userId());
            }
        }
        Set<String> otherAccounts = new HashSet<>();
        Map<UUID, Long> existingSavings = new HashMap<>();
        for (OpeningBalanceSheets.MemberRow row : sheets.members()) {
            if (row.email() == null) {
                continue;
            }
            String email = lower(row.email());
            UUID existing = seated.get(email);
            if (existing != null) {
                existingSavings.put(existing, savingsProducts.products(chamaId).stream()
                        .mapToLong(p -> ledger.memberBalance(chamaId, existing, p.ledgerAccountCode()).minorUnits())
                        .sum());
            } else if (users.findByEmail(email).isPresent()) {
                otherAccounts.add(email);
            }
        }
        int maxMembers = chamas.license(chamaId).map(LicenseSummary::maxMembers).orElse(Integer.MAX_VALUE);
        OpeningBalanceCheck.Chama context = new OpeningBalanceCheck.Chama(LocalDate.now(clock.withZone(zone)), seated,
                otherAccounts, existingSavings, loanImport.borrowersWithOpenLoans(chamaId), memberImport.roleNames(),
                investmentImport.typeNames(), LoanImport.MAX_TERM_MONTHS, maxMembers,
                contributions.earliestDueDate(chamaId), chama.currency());
        return OpeningBalanceCheck.check(sheets, asOf, context);
    }

    /**
     * The Members sheet's extra columns: one per active savings product besides the default (whose
     * balances go in "Savings"), headed by the product's name and code, e.g. "Share capital (SHR, KES)".
     */
    private Map<String, SavingsProductSummary> otherSavingsColumns(UUID chamaId) {
        String currency = chamas.find(chamaId).map(ChamaSummary::currency).orElse("KES");
        Map<String, SavingsProductSummary> columns = new LinkedHashMap<>();
        for (SavingsProductSummary p : savingsProducts.products(chamaId)) {
            if (p.active() && !p.defaultProduct()) {
                columns.put(p.name() + " (" + p.code() + ", " + currency + ")", p);
            }
        }
        return columns;
    }

    private UUID postOpeningEntry(UUID chamaId, String currencyCode, OpeningBalancePlan plan, Map<String, UUID> userIds,
                                  Map<String, SavingsProductSummary> columns, UUID importId, UUID actorUserId) {
        Currency currency = Currency.getInstance(currencyCode);
        List<PostingLine> lines = new ArrayList<>();
        debit(lines, LedgerAccountKey.CASH, plan.cashMinor(), currency, null, null);
        debit(lines, LedgerAccountKey.BANK, plan.bankMinor(), currency, null, null);
        debit(lines, LedgerAccountKey.MOBILE_MONEY, plan.mobileMoneyMinor(), currency, null, null);
        for (PlannedInvestment i : plan.investments()) {
            debit(lines, LedgerAccountKey.INVESTMENTS, i.amountMinor(), currency, null, i.name());
        }
        for (PlannedLoan l : plan.loans()) {
            debit(lines, LedgerAccountKey.LOANS_RECEIVABLE, l.outstandingMinor(), currency, userIds.get(l.memberEmail()), null);
        }
        for (MemberBalance b : plan.balances()) {
            debit(lines, LedgerAccountKey.PENALTIES_RECEIVABLE, b.penaltiesMinor(), currency, userIds.get(b.email()), null);
        }
        for (MemberBalance b : plan.balances()) {
            if (b.savingsMinor() > 0) {
                lines.add(PostingLine.credit(LedgerAccountKey.MEMBER_SAVINGS, Money.of(b.savingsMinor(), currency))
                        .forMember(userIds.get(b.email())));
            }
            b.otherSavingsMinor().forEach((column, minor) -> {
                SavingsProductSummary product = columns.get(column);
                lines.add(new PostingLine(null, product.ledgerAccountCode(), EntrySide.CREDIT, Money.of(minor, currency),
                        userIds.get(b.email()), product.name()));
            });
        }
        long surplus = plan.retainedSurplusMinor();
        if (surplus > 0) {
            lines.add(PostingLine.credit(LedgerAccountKey.RETAINED_SURPLUS, Money.of(surplus, currency))
                    .withMemo("Surplus brought forward"));
        } else if (surplus < 0) {
            lines.add(PostingLine.debit(LedgerAccountKey.RETAINED_SURPLUS, Money.of(-surplus, currency))
                    .withMemo("Deficit brought forward: savings exceed what the chama holds"));
        }
        if (lines.size() < 2) {
            return null;
        }
        return ledger.post(new PostingRequest(chamaId, plan.asOf(), "Opening balances brought forward as at "
                + plan.asOf(), null, SOURCE, importId.toString(), actorUserId, lines));
    }

    private static void debit(List<PostingLine> lines, LedgerAccountKey account, long minor, Currency currency,
                              UUID member, String memo) {
        if (minor <= 0) {
            return;
        }
        PostingLine line = PostingLine.debit(account, Money.of(minor, currency));
        if (member != null) {
            line = line.forMember(member);
        }
        lines.add(memo == null ? line : line.withMemo(memo));
    }

    private SummaryView summary(UUID chamaId, OpeningBalancePlan plan) {
        String currency = chamas.find(chamaId).map(ChamaSummary::currency).orElse("KES");
        int existing = (int) plan.balances().stream().filter(b -> b.existingUserId() != null).count();
        return new SummaryView(currency, plan.newMembers().size(), existing, plan.savingsMinor(), plan.loans().size(),
                plan.loansOutstandingMinor(), plan.penaltiesMinor(), plan.cashMinor(), plan.bankMinor(),
                plan.mobileMoneyMinor(), plan.investments().size(), plan.investmentsMinor(), plan.retainedSurplusMinor());
    }

    private static ProblemView view(Problem p) {
        return new ProblemView(p.severity(), p.sheet(), p.row(), p.column(), p.message());
    }

    private static String lower(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
