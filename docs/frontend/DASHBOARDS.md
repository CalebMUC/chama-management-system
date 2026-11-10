# Dashboards: frontend guide

Last updated 10 Nov 2026 · Companion to the [frontend build guide](README.md) and the [API reference](API_REFERENCE.md#14-dashboards-apiv1dashboard)

## Overview

Six endpoints under `/api/v1/dashboard` feed two home screens and three drill-downs: the chama overview (a full version for officials and a members' version), the member home for everyone, and full rankings and trends behind "See all" links. Every response is wrapped as `{ "data": … }` and is scoped to the chama in the caller's token.

| Endpoint | Who can call it | Licence feature | Returns | Screen |
| --- | --- | --- | --- | --- |
| `GET /dashboard` | `REPORT_VIEW_ALL` | none | `ChamaDashboardView` | Chama overview (`/chama`) |
| `GET /dashboard/overview` | `CHAMA_VIEW` (every role) | none | `ChamaOverviewView` | Chama overview (`/chama`) for members |
| `GET /dashboard/top-members?from=&to=&limit=10` | `REPORT_VIEW_ALL` | `CONTRIBUTIONS` | `TopMembersView` | Top members (`/chama/top-members`) |
| `GET /dashboard/top-investments?limit=5` | `INVESTMENT_VIEW` | `INVESTMENTS` | `TopInvestmentsView` | Top investments (`/chama/top-investments`) |
| `GET /dashboard/trends?months=12` | `REPORT_VIEW_ALL` | none | `TrendsView` | Trends chart (on `/chama`, full view `/chama/trends`) |
| `GET /dashboard/me` | any active member | none | `MemberDashboardView` | Member home (`/home`) |

The overview already carries the top 5 members, top 5 investments and a six-month trend, so the drill-down endpoints are only needed for longer lists, other date ranges or more months. The routes in the last column are suggestions; keep them if they fit the app's router.

## Conventions

All money is an integer in minor units (cents) of the view's `currency`, and all rates are integer basis points. Format both on the client; never do arithmetic on formatted strings.

| Kind | Field suffix | Example | Display |
| --- | --- | --- | --- |
| Money | `…Minor` | `1250000` with `currency: "KES"` | KES 12,500.00 |
| Rate | `…BasisPoints` | `1250` | 12.5% (value ÷ 100) |
| Date | dates such as `asOf`, `dueDate`, `fundedOn` | `"2026-11-09"` | 9 Nov 2026 |
| Month | `month` in trends | `"2026-11"` | Nov 2026 |
| Instant | `generatedAt` | `"2026-11-09T12:30:00Z"` | local time, "generated 3:30 pm" |

- **Null means "not part of this chama".** A section, list or key figure is `null` when the chama's licence lacks its feature (no `loans` without `LOANS`, no `topInvestments` without `INVESTMENTS`). Hide it; never show it as zero. `features` lists what the licence includes.
- **Zero means "nothing yet".** A licensed section with no activity comes back with zeros and empty lists. Show an empty state, not a hidden card.
- **Dates follow the chama's time zone** (Africa/Nairobi). "This month" and "year to date" run from the 1st and from 1 January in that zone, up to `asOf` inclusive.
- **Negative money is allowed** in `surplusMinor`, `totalReturnMinor`, `unrealisedGainMinor`, `netPositionMinor` and `netWorthMinor`. Show it in red with a minus sign, and label a negative surplus "deficit".
- **Compliance** (`complianceRateBasisPoints`) is the share of a member's dues already due that are fully paid. A member with no dues yet scores 10000 (100%).
- **Member ranking:** most contributed in the window first, then higher compliance, then lower member number. Members who paid nothing in the window are left out.
- **Investment ranking:** highest `returnBasisPoints` first, then the larger `totalReturnMinor`. Active and closed investments are both ranked; proposed, rejected or unfunded ones never appear.

## Chama overview (`GET /dashboard`)

One call fills the whole officials' screen; lay it out top-down as KPI row, "Needs attention", then the section cards, then rankings, trend and recent entries. Show the link to this screen only to users with `REPORT_VIEW_ALL`; everyone else gets `403 ACCESS_DENIED`.

**Header:** `chamaName`, "As of {asOf}", "generated {generatedAt}" and a refresh button.

**KPI row** from `keyFigures` (hide any tile whose value is `null`):

| Tile | Field | Subtext |
| --- | --- | --- |
| Total contributions | `totalContributionsMinor` | "{contributionsYearToDateMinor} this year" |
| Savings | `totalSavingsMinor` | number of products from `savings.products` |
| Cash | `cashMinor` | cash / bank / M-Pesa split from `cash` |
| Loans outstanding | `loansOutstandingMinor` | PAR30 from `loans.par30BasisPoints` |
| Investments | `investmentsMarketValueMinor` | return % from `investments.returnBasisPoints` |
| Penalties owed | `penaltiesOutstandingMinor` | "{penalties.membersOwing} members" |
| Net worth | `netWorthMinor` | "Assets {totalAssetsMinor}" |

**Needs attention** from `pending`: one row per non-zero count, each linking to its filtered list. `null` counts are unlicensed; skip them.

| Count | Link |
| --- | --- |
| `membershipApplications` | `/members?status=PENDING_APPROVAL` |
| `paymentsAwaitingReview` (split: `contributionsAwaitingReview`, `penaltyPaymentsAwaitingReview`, `loanRepaymentsAwaitingReview`) | `/payment-submissions?status=PENDING` |
| `loansAwaitingApproval` | `/loans?status=PENDING_APPROVAL` |
| `loansAwaitingDisbursement` | `/loans?status=APPROVED` |
| `expensesAwaitingApproval` | `/expenses?status=PENDING_APPROVAL` |
| `expensesAwaitingPayment` | `/expenses?status=APPROVED` |
| `investmentsAwaitingApproval` | `/investments?status=PROPOSED` |

**Section cards** (hide a card whose section is `null`):

| Card | Show | Notes |
| --- | --- | --- |
| Members (`members`) | active; suspended and pending applications as subtext; joined this month / this year | |
| Contributions (`contributions`) | `currentPeriod`, `dueDate`, collected vs expected as a progress bar (`collectionRateBasisPoints`), members paid / late; total arrears and members in arrears; collected this month (with `paymentsThisMonth`) and this year; compliance % | `currentPeriod` is `null` before the first period: show "No contribution period yet" |
| Loans (`loans`) | active loans, outstanding, arrears, loans in arrears, PAR30; ageing buckets from `aging`; disbursed this year; total disbursed vs repaid; interest expected; "{dueNext7Days} instalments due this week ({dueNext7DaysMinor})" | PAR30 amber above 5%, red above 10% |
| Penalties (`penalties`) | owed now (amount, count, members); this year: assessed (late contributions vs fines), collected, waived; `topOwing` list | each `topOwing` row links to the member |
| Investments (`investments`) | active count, invested, market value, unrealised gain, income; total return and return % across everything funded; profitable count; closed count; maturing in 30 days | gain and return can be negative |
| Savings (`savings`) | total and one row or bar per product | inactive products appear only while they still hold money |
| Performance (`thisMonth`, `yearToDate`) | income, expenses, surplus side by side | negative surplus = "deficit" in red |

**Rankings:** `topMembers` (up to 5) and `topInvestments` (up to 5) as compact lists with "See all" links to the drill-down screens below. Hide each list when `null`; when empty, show "No contributions this year yet" or "No funded investments yet".

**Trend:** draw `trends` (6 months) as described under [Trends](#trends-get-dashboardtrends).

**Recent entries:** `recentEntries` (10 newest ledger entries): number, date, description, source type chip, amount; mark reversals.

## Chama overview for members (`GET /dashboard/overview`)

Every member may see how the chama as a whole is doing. Use the same `/chama` screen: fetch `GET /dashboard` when the user has `REPORT_VIEW_ALL`, otherwise `GET /dashboard/overview` when they have `CHAMA_VIEW`. Show the "Chama overview" link to anyone with `CHAMA_VIEW`.

`ChamaOverviewView` has the same fields as `ChamaDashboardView` with the same meaning, except:

| Left out or emptied | Why | On screen |
| --- | --- | --- |
| `pending` | an official's work queue | no "Needs attention" panel |
| `topMembers` | names individual members | no top-members list or "See all" |
| `penalties.topOwing` (always `[]`) | names individual members | hide the "members owing most" list; totals stay |
| `recentEntries` | ledger detail | no recent entries |
| `investments`, `topInvestments`, `keyFigures.investmentsMarketValueMinor` | `null` when the viewer lacks `INVESTMENT_VIEW` (members have it by default) | hide the investments card and tile |
| `penaltiesOutstandingMinor` (legacy) | use `penalties.outstandingMinor` | — |

The drill-downs behind `topMembers` and `trends` need `REPORT_VIEW_ALL`, so for members draw the six-month `trends` from this view and don't link to `/chama/trends`. "See all" on top investments still works for anyone with `INVESTMENT_VIEW`.

## Top members (`GET /dashboard/top-members`)

This screen ranks members by what they paid between `from` and `to`, and defaults to this year. Reach it from "See all" on the overview's top-members list; hide that link when `features` lacks `CONTRIBUTIONS`.

**Query parameters**

| Parameter | Default | Rule |
| --- | --- | --- |
| `from` | 1 January of `to`'s year | ISO date, inclusive |
| `to` | today | ISO date, inclusive |
| `limit` | 10 | values outside 1–50 are clamped, not refused |

**Controls:** a date-range picker with presets "This month", "This year" (default), "Last year" and "Custom", plus a "Show 10 / 25 / 50" selector. Keep the range in the URL (`?from=&to=`) so it can be shared. Show the range the server used (`from`, `to` in the response) as the subtitle.

**Table columns**

| Column | Field | Format |
| --- | --- | --- |
| # | `rank` | 1, 2, 3 get a medal chip |
| Member | `name`, `memberNumber` | name, with "#12" muted; links to the member |
| Contributed | `contributedMinor` | money; the sort column |
| Payments | `payments` | count |
| Compliance | `complianceRateBasisPoints` | % with a bar; green ≥ 90%, amber 70–89%, red < 70% |
| Arrears | `arrearsMinor` | money, red if > 0 |
| Late periods | `latePeriods` | count |
| Savings | `savingsMinor` | money held in all savings products today |

`arrearsMinor`, `latePeriods`, `complianceRateBasisPoints` and `savingsMinor` are as of today, whatever range is chosen. Say so in a footnote.

**States**

- Empty list: "No contributions recorded between {from} and {to}."
- `from` after `to`: `400 REPORT_INVALID_PERIOD`. Stop it in the picker; if it still happens, show the error message.
- `403 LICENSE_FEATURE_NOT_INCLUDED`: the chama lacks `CONTRIBUTIONS`; route back to the overview.

## Top investments (`GET /dashboard/top-investments`)

This screen ranks every funded investment, active or closed, by return on what was put in. Anyone with `INVESTMENT_VIEW` may open it (plain members have it by default), so link it from both the overview and the member home. `limit` defaults to 5 and is clamped to 1–50; use 50 for the full screen.

**How the return is worked out** (by the server; the client only displays it):

```
totalReturn = income − expenses + gain in value
```

The gain in value is realised on closed investments, and on active ones is the latest valuation less book value (0 when never valued). `returnBasisPoints` is that return per 10,000 invested.

**Table columns**

| Column | Field | Format |
| --- | --- | --- |
| # | `rank` | |
| Investment | `name`, `type`, `institution` | name links to `/investments/{investmentId}`; type as a chip |
| Status | `status` | `ACTIVE` or `CLOSED` chip |
| Invested | `investedMinor` | money |
| Value | `marketValueMinor` | money |
| Income | `incomeMinor` | money |
| Expenses | `expensesMinor` | money; paid expenses charged to the investment |
| Return | `totalReturnMinor`, `returnBasisPoints` | money and %; green when `profitable`, red when negative, neutral at 0 |
| Per year | `annualisedReturnBasisPoints` | %; show "—" when `null` (held under 30 days) |
| Held | `daysHeld`, `fundedOn`, `maturityDate` | "142 days"; tooltip with dates; "Matures {date}" when set |

**States**

- Empty list: "No funded investments yet." Officials with `INVESTMENT_MANAGE` also get a "Propose an investment" button.
- `403 ACCESS_DENIED` (no `INVESTMENT_VIEW`) or `403 LICENSE_FEATURE_NOT_INCLUDED`: hide the entry points instead of showing the error.

## Trends (`GET /dashboard/trends`)

The trend is a monthly series, oldest first, ending with the current month to date. The overview's `trends` field holds the last 6 months; this endpoint returns 1–24 (default 12) and refuses anything else with `400 REPORT_INVALID_PERIOD`.

**Chart:** one chart, months on the x-axis ("Nov 2026"), money on the y-axis.

| Series | Field | Mark |
| --- | --- | --- |
| Contributions | `contributionsMinor` | bars; tooltip adds `contributionPayments` |
| Loans disbursed | `loansDisbursedMinor` | bars beside contributions |
| Income | `incomeMinor` | line |
| Expenses | `expensesMinor` | line |
| Surplus | `surplusMinor` | bars about zero, or a figure in the tooltip; negative is a deficit |

- Drop a series whose field is `null` (contributions without `CONTRIBUTIONS`, loans without `LOANS`). Income, expenses and surplus are always present.
- Mark the last month as partial (hatched bar or "to date" in the label).
- Offer a 6 / 12 / 24-month toggle on the full view; it refetches with `?months=`.
- Months with no activity come back as zeros, never missing, so the x-axis has no gaps.

## Member home (`GET /dashboard/me`)

Every active member lands here, officials included. The screen leads with the member's net position, then one card per licensed section. It always describes the caller; there is no member id parameter.

**Header:** greeting with `memberName` and "Member #{memberNumber}", `chamaName`. If the user has `CHAMA_VIEW` (every role by default), add a "Chama overview" link; it opens the members' version unless they also have `REPORT_VIEW_ALL`.

**Net position** (`netPositionMinor`): savings less what the member owes on loans and penalties. Large figure; green when positive, red when negative, with a one-line breakdown "Savings {savings.totalMinor} − loans {loans.outstandingMinor} − penalties {penalties.outstandingMinor}" (leave out terms whose section is `null`).

| Card | Show | Actions |
| --- | --- | --- |
| Savings (`savings`) | total; each account in `accounts`: product name, account number, balance; inactive accounts muted | row links to `/me/savings/{accountId}` |
| Contributions (`contributions`) | `currentPeriod` with a status chip from `currentPeriodStatus` (`PAID` green, `PARTIALLY_PAID` amber, `UNPAID` red); arrears (red if > 0), advance, late periods; paid this year; compliance %; "Ranked {rank} of {rankOutOf} this year" | "Report a payment" when arrears > 0 or the period is unpaid; "View contributions" |
| Loans (`loans`) | outstanding, arrears, next instalment (`nextDueDate`, `nextDueMinor`); total borrowed vs repaid; one row per loan in `loans.loans` with product, status chip, outstanding and a progress bar from `repaidBasisPoints`; days in arrears in red when > 0 | "Respond" badge when `guaranteeRequests` > 0; "Apply for a loan" with `LOAN_APPLY`; row links to the loan |
| Penalties (`penalties`) | owed now (amount, count); paid and waived to date; each item in `outstanding`: description, assessed date, amount still owed | "Pay" per penalty opens the payment report form |
| Pending reports (`submissions`) | "{pending} payments awaiting confirmation" when > 0 | links to `GET /payment-submissions/me` list |
| Our investments (`topInvestments`) | up to 3 by return: name, return %, profit/loss colour | "See all" → top investments |

**Notes**

- `contributions.rank` is `null` when the member has paid nothing this year: show "Not ranked yet" instead of a number.
- `currentPeriod` and `currentPeriodStatus` are `null` when nothing is owed yet: hide the chip.
- `loans.loans` leaves out rejected and cancelled applications; `inProgress` counts applications still being processed.
- `topInvestments` is `null` both when the licence lacks `INVESTMENTS` and when the member lacks `INVESTMENT_VIEW`. Either way, hide the card.
- The payment report form posts to `POST /payment-submissions`; after a submit, refetch this view so `submissions.pending` goes up.

## Errors, caching and refresh

The dashboards are computed on every request, with no server cache and no push. The client decides freshness: refetch on focus and after the user's own writes, and poll only the work-queue counts.

**Errors**

| Status | `error.code` | When | Handle by |
| --- | --- | --- | --- |
| 400 | `REPORT_INVALID_PERIOD` | `from` after `to`; `months` outside 1–24 | validate in the control; show the message |
| 403 | `ACCESS_DENIED` | missing permission (`REPORT_VIEW_ALL`, `INVESTMENT_VIEW`) | hide the entry point; on direct navigation, redirect to `/home` |
| 403 | `LICENSE_FEATURE_NOT_INCLUDED` | top members without `CONTRIBUTIONS`, top investments without `INVESTMENTS` | hide the entry point using `features` |
| 404 | `REPORT_MEMBER_NOT_FOUND` | `/me` for a user without an active seat (e.g. just suspended) | sign out of the chama or show "Your membership is not active" |

**Query keys**

| Key | Request |
| --- | --- |
| `dashboard` | `GET /dashboard` |
| `dashboard.me` | `GET /dashboard/me` |
| `dashboard.topMembers(from, to, limit)` | `GET /dashboard/top-members` |
| `dashboard.topInvestments(limit)` | `GET /dashboard/top-investments` |
| `dashboard.trends(months)` | `GET /dashboard/trends` |

Keep all five under the `dashboard` prefix so that invalidating `dashboard` refreshes the drill-downs too.

**Invalidate after writes** (in addition to each area's own keys):

| Write | Invalidate |
| --- | --- |
| Record / reverse a contribution; approve a contribution report | `dashboard` (prefix), `dashboard.me` |
| Submit, cancel, approve or reject a payment report | `dashboard` (prefix), `dashboard.me` |
| Fine, penalty payment, waiver | `dashboard` (prefix), `dashboard.me` |
| Loan apply / approve / disburse / repayment | `dashboard` (prefix), `dashboard.me` |
| Investment fund / income / valuation / withdrawal / close; expense charged to an investment | `dashboard` (prefix) |
| Member approved, suspended, roles changed | `dashboard` (prefix), and `dashboard.me` if the user is affected |

**Refresh:** refetch both home screens on window focus. Poll `GET /dashboard` every 2–5 minutes at most, only while the overview is visible, for the "Needs attention" counts. Keep previous data on screen while refetching so cards don't flash.

## Response shapes

These TypeScript types match the backend records field for field (`backend/src/main/java/ke/chama/modules/report/application/port/in/DashboardViews.java`). The same types, with enums such as `LoanStatus` filled in, are in [`api-types.ts`](api-types.ts): copy that file, not this block. `RankedInvestment` is the Java `InvestmentPerformance` record, renamed because `api-types.ts` already uses that name for the investment detail page. `Minor` = integer cents, `Bp` = integer basis points, `IsoDate` = `"YYYY-MM-DD"`.

```typescript
type Minor = number; type Bp = number; type IsoDate = string; type Uuid = string;

// GET /dashboard
interface ChamaDashboardView {
  chamaName: string; asOf: IsoDate; currency: string; features: string[];
  keyFigures: KeyFigures;
  members: MembersSection; cash: CashSection;
  savings: SavingsSection | null;             // CONTRIBUTIONS
  contributions: ContributionsSection | null; // CONTRIBUTIONS
  loans: LoansSection | null;                 // LOANS
  penaltiesOutstandingMinor: Minor | null;    // legacy; same as penalties.outstandingMinor
  penalties: PenaltiesSection | null;         // PENALTIES
  investments: InvestmentsSection | null;     // INVESTMENTS
  topMembers: TopMember[] | null;             // up to 5, CONTRIBUTIONS
  topInvestments: RankedInvestment[] | null; // up to 5, INVESTMENTS
  thisMonth: PerformanceSection; yearToDate: PerformanceSection;
  trends: MonthTrend[];                       // 6 months, oldest first
  pending: PendingSection;
  recentEntries: EntrySummary[];              // 10 newest
  generatedAt: string;                        // ISO instant
}
interface KeyFigures {
  totalContributionsMinor: Minor | null; contributionsYearToDateMinor: Minor | null;
  totalSavingsMinor: Minor | null; cashMinor: Minor; loansOutstandingMinor: Minor | null;
  investmentsMarketValueMinor: Minor | null; penaltiesOutstandingMinor: Minor | null;
  totalAssetsMinor: Minor; netWorthMinor: Minor;
}
interface MembersSection { active: number; suspended: number; pendingApplications: number; joinedThisMonth: number; joinedThisYear: number; }
interface CashSection { cashOnHandMinor: Minor; bankMinor: Minor; mobileMoneyMinor: Minor; totalMinor: Minor; }
interface SavingsSection { totalMinor: Minor; products: { productId: Uuid; code: string; name: string; balanceMinor: Minor }[]; }
interface ContributionsSection {
  currentPeriod: string | null; dueDate: IsoDate | null; expectedMinor: Minor; collectedMinor: Minor;
  collectionRateBasisPoints: Bp; membersPaid: number; membersLate: number;
  arrearsMinor: Minor; membersInArrears: number;
  totalCollectedMinor: Minor; collectedThisMonthMinor: Minor; collectedYearToDateMinor: Minor;
  paymentsThisMonth: number; complianceRateBasisPoints: Bp;
}
interface LoansSection {
  activeLoans: number; outstandingMinor: Minor; arrearsMinor: Minor; loansInArrears: number;
  par30BasisPoints: Bp; awaitingApproval: number; awaitingDisbursement: number;
  aging: { bucket: 'CURRENT' | 'DAYS_1_30' | 'DAYS_31_60' | 'DAYS_61_90' | 'OVER_90'; label: string;
           loans: number; outstandingMinor: Minor; arrearsMinor: Minor }[];
  disbursedYearToDate: number; disbursedYearToDateMinor: Minor;
  totalDisbursedMinor: Minor; totalRepaidMinor: Minor; repaidLoans: number;
  interestExpectedMinor: Minor; dueNext7Days: number; dueNext7DaysMinor: Minor;
}
interface PenaltiesSection {
  outstandingMinor: Minor; outstandingCount: number; membersOwing: number;
  assessedYearToDateMinor: Minor; lateContributionYearToDateMinor: Minor; finesYearToDateMinor: Minor;
  collectedYearToDateMinor: Minor; waivedYearToDateMinor: Minor;
  topOwing: { memberUserId: Uuid; name: string | null; memberNumber: number | null; amountMinor: Minor; count: number }[];
}
interface InvestmentsSection {
  active: number; investedMinor: Minor; marketValueMinor: Minor; incomeMinor: Minor; bookValueMinor: Minor;
  unrealisedGainMinor: Minor; totalReturnMinor: Minor; returnBasisPoints: Bp;
  profitable: number; closed: number; maturingIn30Days: number;
}
interface PerformanceSection { from: IsoDate; to: IsoDate; incomeMinor: Minor; expensesMinor: Minor; surplusMinor: Minor; }
interface PendingSection {
  membershipApplications: number;
  loansAwaitingApproval: number | null; loansAwaitingDisbursement: number | null;
  expensesAwaitingApproval: number | null; expensesAwaitingPayment: number | null;
  investmentsAwaitingApproval: number | null;
  paymentsAwaitingReview: number; contributionsAwaitingReview: number;
  penaltyPaymentsAwaitingReview: number; loanRepaymentsAwaitingReview: number;
}
interface EntrySummary { id: Uuid; number: number; entryDate: IsoDate; description: string; reference: string | null; sourceType: string; totalMinor: Minor; reversal: boolean; }

// GET /dashboard/top-members
interface TopMembersView { from: IsoDate; to: IsoDate; currency: string; members: TopMember[]; }
interface TopMember {
  rank: number; memberUserId: Uuid; name: string; memberNumber: number | null;
  contributedMinor: Minor; payments: number; complianceRateBasisPoints: Bp;
  arrearsMinor: Minor; latePeriods: number; savingsMinor: Minor;
}

// GET /dashboard/top-investments
interface TopInvestmentsView { currency: string; investments: RankedInvestment[]; }
interface RankedInvestment {
  rank: number; investmentId: Uuid; name: string; type: string; institution: string | null;
  status: 'ACTIVE' | 'CLOSED'; investedMinor: Minor; marketValueMinor: Minor; incomeMinor: Minor;
  expensesMinor: Minor; totalReturnMinor: Minor; returnBasisPoints: Bp;
  annualisedReturnBasisPoints: Bp | null; daysHeld: number; profitable: boolean;
  fundedOn: IsoDate | null; maturityDate: IsoDate | null;
}

// GET /dashboard/trends
interface TrendsView { currency: string; months: MonthTrend[]; }
interface MonthTrend {
  month: string; // "YYYY-MM"
  contributionsMinor: Minor | null; contributionPayments: number | null; loansDisbursedMinor: Minor | null;
  incomeMinor: Minor; expensesMinor: Minor; surplusMinor: Minor;
}

// GET /dashboard/me
interface MemberDashboardView {
  chamaName: string; memberUserId: Uuid; memberName: string; memberNumber: number | null;
  currency: string; features: string[]; netPositionMinor: Minor;
  savings: { totalMinor: Minor; accounts: { accountId: Uuid; accountNumber: string; productCode: string;
             productName: string; active: boolean; balanceMinor: Minor }[] } | null;
  contributions: {
    totalPaidMinor: Minor; arrearsMinor: Minor; advanceMinor: Minor; latePeriods: number;
    currentPeriod: string | null; currentPeriodStatus: 'PAID' | 'PARTIALLY_PAID' | 'UNPAID' | null;
    contributedYearToDateMinor: Minor; complianceRateBasisPoints: Bp; rank: number | null; rankOutOf: number;
  } | null;
  loans: {
    activeLoans: number; inProgress: number; outstandingMinor: Minor; arrearsMinor: Minor;
    nextDueDate: IsoDate | null; nextDueMinor: Minor; guaranteeRequests: number;
    totalBorrowedMinor: Minor; totalRepaidMinor: Minor; loans: MyLoan[];
  } | null;
  penaltiesOutstandingMinor: Minor | null; // legacy; same as penalties.outstandingMinor
  penalties: {
    outstandingMinor: Minor; outstandingCount: number; paidMinor: Minor; waivedMinor: Minor;
    outstanding: { penaltyId: Uuid; kind: string; description: string; assessedOn: IsoDate;
                   amountMinor: Minor; outstandingMinor: Minor }[]; // oldest first
  } | null;
  submissions: { pending: number };
  topInvestments: RankedInvestment[] | null; // up to 3
  generatedAt: string;
}
interface MyLoan {
  loanId: Uuid; productName: string; status: string; purpose: string | null;
  principalMinor: Minor; interestMinor: Minor; totalDueMinor: Minor; repaidMinor: Minor;
  outstandingMinor: Minor; arrearsMinor: Minor; daysInArrears: number; repaidBasisPoints: Bp;
  termMonths: number; appliedOn: IsoDate | null; disbursedOn: IsoDate | null;
  maturityDate: IsoDate | null; nextDueDate: IsoDate | null; nextDueMinor: Minor;
}
```

`penaltiesOutstandingMinor` at the top level of both home views is kept for older clients; new code should read `penalties.outstandingMinor`.
