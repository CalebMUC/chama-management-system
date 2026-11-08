/**
 * Chama Platform API: TypeScript contracts for the frontend.
 *
 * Written by hand from the Spring Boot backend (backend/src/main/java/ke/chama), November 2026.
 * The running backend's OpenAPI document (GET /v3/api-docs) is the final authority; if the two
 * ever disagree, regenerate or fix this file from it.
 *
 * Conventions used by every type below:
 *  - UUID        a lowercase UUID string.
 *  - ISODate     a calendar date "2026-11-01". Business dates are days in Africa/Nairobi.
 *  - ISOInstant  a UTC timestamp "2026-11-06T09:00:00Z" (may carry fractional seconds).
 *  - Minor       an integer amount in the currency's minor unit (KES cents: 150050 = KES 1,500.50).
 *                Never a float. Divide only for display.
 *  - BasisPoints an integer rate: 100 = 1%, 1250 = 12.5%, 10000 = 100%.
 *  - `T | null`  the backend sends the key with null; it does not omit it.
 *  - `field?:`   (requests only) may be left out.
 */

// =====================================================================================================
// Primitives
// =====================================================================================================

export type UUID = string;
export type ISODate = string;
export type ISOInstant = string;
export type Minor = number;
export type BasisPoints = number;

// =====================================================================================================
// Response envelope (every endpoint, success or failure)
// =====================================================================================================

export interface PageMeta {
  /** Zero-based. */
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface FieldError {
  /** JSON property path as sent, e.g. "email", "settings.loanMaxMultiplier", "lines[1].accountCode", or a query param name. */
  field: string;
  message: string;
}

export interface ApiError {
  /** Stable machine-readable code. Branch on this, never on `message`. See ErrorCode. */
  code: ErrorCode | (string & {});
  /** HTTP status repeated in the body. */
  status: number;
  path?: string;
  /** Always present; empty unless the error is about specific inputs. */
  fields: FieldError[];
  /** Only on 429: seconds to wait (also sent as the Retry-After header). */
  retryAfterSeconds?: number;
}

export interface ApiSuccess<T> {
  success: true;
  /** Human-readable, safe to show to the user (toast). */
  message: string;
  data: T;
  /** Only on paged lists. */
  meta?: PageMeta;
  timestamp: ISOInstant;
}

export interface ApiFailure {
  success: false;
  /** Human-readable, safe to show to the user. */
  message: string;
  data: null;
  error: ApiError;
  timestamp: ISOInstant;
}

export type ApiResponse<T> = ApiSuccess<T> | ApiFailure;

/** A paged list: `data` is the array, `meta` the paging. */
export type Paged<T> = ApiSuccess<T[]> & { meta: PageMeta };

/** Query params accepted by every paged list. size is clamped to 1..100 by the server; default 50. */
export interface PageParams {
  page?: number;
  size?: number;
}

// =====================================================================================================
// Enums
// =====================================================================================================

/** Global (platform) roles carried in the access token. Chama roles are separate (ChamaRole). */
export type PlatformRole = 'ROLE_USER' | 'ROLE_ADMIN';

/** Roles inside one chama. A member can hold several; every member always holds MEMBER. */
export type ChamaRole = 'MEMBER' | 'TREASURER' | 'SECRETARY' | 'CHAIRPERSON' | 'AUDITOR';

/**
 * Everything a member can be allowed to do. The UI must gate on these (from GET /members/me),
 * never on role names: each chama can re-map roles to permissions.
 */
export type ChamaPermission =
  | 'CHAMA_VIEW'
  | 'CHAMA_SETTINGS_MANAGE'
  | 'MEMBER_VIEW'
  | 'MEMBER_INVITE'
  | 'MEMBER_APPROVE'
  | 'MEMBER_SUSPEND'
  | 'MEMBER_ROLES_MANAGE'
  | 'ROLE_PERMISSIONS_MANAGE'
  | 'CONTRIBUTION_VIEW_ALL'
  | 'CONTRIBUTION_RECORD'
  | 'CONTRIBUTION_REVERSE'
  | 'LOAN_APPLY'
  | 'LOAN_VIEW_ALL'
  | 'LOAN_APPROVE'
  | 'LOAN_DISBURSE'
  | 'LOAN_REPAYMENT_RECORD'
  | 'PENALTY_VIEW_ALL'
  | 'PENALTY_RECORD'
  | 'PENALTY_WAIVE'
  | 'EXPENSE_VIEW'
  | 'EXPENSE_CREATE'
  | 'EXPENSE_APPROVE'
  | 'INVESTMENT_VIEW'
  | 'INVESTMENT_MANAGE'
  | 'INVESTMENT_APPROVE'
  | 'LEDGER_VIEW'
  | 'LEDGER_POST'
  | 'LEDGER_ACCOUNTS_MANAGE'
  | 'REPORT_VIEW_ALL'
  | 'AUDIT_LOG_VIEW'
  | 'PRODUCTS_MANAGE'
  | 'MEMBER_ACCOUNTS_VIEW_ALL'
  | 'MEMBER_ACCOUNTS_MANAGE';

export const ALL_PERMISSIONS: readonly ChamaPermission[] = [
  'CHAMA_VIEW', 'CHAMA_SETTINGS_MANAGE',
  'MEMBER_VIEW', 'MEMBER_INVITE', 'MEMBER_APPROVE', 'MEMBER_SUSPEND', 'MEMBER_ROLES_MANAGE', 'ROLE_PERMISSIONS_MANAGE',
  'CONTRIBUTION_VIEW_ALL', 'CONTRIBUTION_RECORD', 'CONTRIBUTION_REVERSE',
  'LOAN_APPLY', 'LOAN_VIEW_ALL', 'LOAN_APPROVE', 'LOAN_DISBURSE', 'LOAN_REPAYMENT_RECORD',
  'PENALTY_VIEW_ALL', 'PENALTY_RECORD', 'PENALTY_WAIVE',
  'EXPENSE_VIEW', 'EXPENSE_CREATE', 'EXPENSE_APPROVE',
  'INVESTMENT_VIEW', 'INVESTMENT_MANAGE', 'INVESTMENT_APPROVE',
  'LEDGER_VIEW', 'LEDGER_POST', 'LEDGER_ACCOUNTS_MANAGE',
  'REPORT_VIEW_ALL', 'AUDIT_LOG_VIEW',
  'PRODUCTS_MANAGE', 'MEMBER_ACCOUNTS_VIEW_ALL', 'MEMBER_ACCOUNTS_MANAGE',
] as const;

/** PENDING_APPROVAL -> ACTIVE <-> SUSPENDED; PENDING_APPROVAL -> REJECTED. EXITED is reserved (no endpoint yet). */
export type MembershipStatus = 'PENDING_APPROVAL' | 'REJECTED' | 'ACTIVE' | 'SUSPENDED' | 'EXITED';

export type ChamaStatus = 'ACTIVE' | 'ARCHIVED';
export type ContributionFrequency = 'MONTHLY' | 'WEEKLY';

export type LicenseFeature =
  | 'CONTRIBUTIONS'
  | 'PENALTIES'
  | 'EXPENSES'
  | 'LOANS'
  | 'INVESTMENTS'
  | 'REPORTS'
  | 'MPESA'
  | 'SMS_NOTIFICATIONS';
export type LicensePlan = 'TRIAL' | 'BASIC' | 'STANDARD' | 'PREMIUM';
/** Derived: whether the chama can operate right now. */
export type LicenseState = 'ACTIVE' | 'EXPIRED' | 'SUSPENDED';
/** What a platform administrator sets. Expiry is not a status. */
export type LicenseStatus = 'ACTIVE' | 'SUSPENDED';

/** How money moved. Decides the ledger account: CASH 1000, BANK 1010, MPESA 1020. */
export type PaymentMethod = 'CASH' | 'BANK' | 'MPESA';
/** Investment income can also be reinvested (adds to book value, no cash moves). */
export type IncomeMethod = PaymentMethod | 'REINVESTED';

export type ContributionPaymentStatus = 'RECORDED' | 'REVERSED';
export type PeriodPaymentStatus = 'PAID' | 'PARTIALLY_PAID' | 'UNPAID';

export type PenaltyKind = 'LATE_CONTRIBUTION' | 'FINE';
export type PenaltyStatus = 'OUTSTANDING' | 'PAID' | 'WAIVED';

export type LoanStatus =
  | 'PENDING_GUARANTORS'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'ACTIVE'
  | 'REPAID'
  | 'REJECTED'
  | 'CANCELLED';
export type GuarantorStatus = 'REQUESTED' | 'ACCEPTED' | 'DECLINED' | 'WITHDRAWN';
export type InstalmentStatus = 'PAID' | 'PARTLY_PAID' | 'DUE' | 'OVERDUE';

export type ExpenseStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'PAID' | 'REJECTED' | 'CANCELLED';

export type InvestmentType =
  | 'MONEY_MARKET_FUND'
  | 'FIXED_DEPOSIT'
  | 'TREASURY_BILL'
  | 'TREASURY_BOND'
  | 'SACCO_SHARES'
  | 'EQUITIES'
  | 'LAND'
  | 'BUSINESS'
  | 'OTHER';
export type InvestmentStatus = 'PROPOSED' | 'APPROVED' | 'ACTIVE' | 'CLOSED' | 'REJECTED' | 'CANCELLED';
export type InvestmentTransactionKind = 'FUNDING' | 'DEPOSIT' | 'INCOME' | 'WITHDRAWAL' | 'CLOSURE';

export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'INCOME' | 'EXPENSE';
/** The built-in accounts every chama has. null on accounts a chama added itself. */
export type LedgerAccountKey =
  | 'CASH'
  | 'BANK'
  | 'MOBILE_MONEY'
  | 'LOANS_RECEIVABLE'
  | 'PENALTIES_RECEIVABLE'
  | 'INVESTMENTS'
  | 'MEMBER_SAVINGS'
  | 'RETAINED_SURPLUS'
  | 'INTEREST_INCOME'
  | 'PENALTY_INCOME'
  | 'INVESTMENT_INCOME'
  | 'OTHER_INCOME'
  | 'OPERATING_EXPENSES'
  | 'BANK_CHARGES';

/** What created a journal entry. Open-ended: show unknown values as-is. */
export type LedgerSourceType =
  | 'CONTRIBUTION'
  | 'PENALTY'
  | 'PENALTY_PAYMENT'
  | 'PENALTY_WAIVER'
  | 'LOAN_DISBURSEMENT'
  | 'LOAN_REPAYMENT'
  | 'EXPENSE'
  | 'INVESTMENT_FUNDING'
  | 'INVESTMENT_DEPOSIT'
  | 'INVESTMENT_INCOME'
  | 'INVESTMENT_WITHDRAWAL'
  | 'INVESTMENT_CLOSURE'
  | 'OPENING_BALANCE'
  | 'MANUAL'
  | 'REVERSAL'
  | (string & {});

export type SavingsProductKind = 'DEPOSITS' | 'SHARE_CAPITAL' | 'WELFARE' | 'CUSTOM';
export type MemberAccountStatus = 'ACTIVE' | 'CLOSED';

export type LoanAgingBucket = 'CURRENT' | 'DAYS_1_30' | 'DAYS_31_60' | 'DAYS_61_90' | 'OVER_90';
export type ImportProblemSeverity = 'ERROR' | 'WARNING';

// =====================================================================================================
// Auth  (/api/v1/auth)
// =====================================================================================================

/** password: 8-72 chars (max 72 UTF-8 bytes), at least one letter and one digit. */
export interface RegisterRequest {
  email: string; // max 255, valid email
  phoneNumber: string; // max 20; 0712345678, 712345678, 254712345678, +254712345678, or +<country><number>
  password: string;
  firstName: string; // max 100
  lastName: string; // max 100
}

export interface LoginRequest {
  email: string;
  password: string; // max 128
}

export interface RefreshTokenRequest {
  refreshToken: string; // max 512
}

export interface TokenRequest {
  /** The `token` query parameter from an emailed link. */
  token: string;
}

export interface EmailRequest {
  email: string;
}

export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: 'Bearer';
  /** Seconds until the access token expires (15 minutes by default). */
  expiresIn: number;
  accessTokenExpiresAt: ISOInstant;
  /** Single-use. Each refresh returns a new one and invalidates this one. */
  refreshToken: string;
  /** 7 days by default. */
  refreshTokenExpiresAt: ISOInstant;
}

export interface UserResponse {
  id: UUID;
  /** null for platform administrators. */
  chamaId: UUID | null;
  /** null for platform administrators. */
  chamaCode: string | null;
  email: string;
  emailVerified: boolean;
  /** E.164, e.g. +254712345678. */
  phoneNumber: string;
  firstName: string;
  lastName: string;
  roles: PlatformRole[];
  createdAt: ISOInstant;
}

// =====================================================================================================
// Chama, settings, licence  (/api/v1/chama, /api/v1/platform/chamas)
// =====================================================================================================

/**
 * The chama's rules. Amounts in minor units, rates in basis points. Server-side ranges:
 *  contributionAmountMinor > 0
 *  contributionDueDay       MONTHLY: 1-28 (day of month) | WEEKLY: 1-7 (1 = Monday)
 *  lateGraceDays            0-31
 *  latePenaltyBasisPoints   0-10000
 *  loanMaxMultiplier        1-10
 *  loanInterestBasisPoints  0-10000 (flat, for the whole term)
 *  loanMinMembershipMonths  0-60
 *  loanGuarantorThresholdMinor >= 0
 *  loanGuarantorsRequired   0-5
 *  approvalQuorum           1-10
 */
export interface ChamaSettings {
  contributionAmountMinor: Minor;
  contributionFrequency: ContributionFrequency;
  contributionDueDay: number;
  lateGraceDays: number;
  latePenaltyBasisPoints: BasisPoints;
  loanMaxMultiplier: number;
  loanInterestBasisPoints: BasisPoints;
  loanMinMembershipMonths: number;
  loanGuarantorThresholdMinor: Minor;
  loanGuarantorsRequired: number;
  approvalQuorum: number;
}

/** PUT /chama/settings takes every field (all required). */
export type ChamaSettingsRequest = ChamaSettings;

/** Defaults a new chama gets when onboarding sends no settings. */
export const STANDARD_SETTINGS: ChamaSettings = {
  contributionAmountMinor: 500_000,
  contributionFrequency: 'MONTHLY',
  contributionDueDay: 5,
  lateGraceDays: 5,
  latePenaltyBasisPoints: 200,
  loanMaxMultiplier: 3,
  loanInterestBasisPoints: 1_000,
  loanMinMembershipMonths: 3,
  loanGuarantorThresholdMinor: 5_000_000,
  loanGuarantorsRequired: 2,
  approvalQuorum: 2,
};

export interface ChamaResponse {
  id: UUID;
  /** Unique login code, sent as X-Chama-Code. Cannot change. */
  code: string;
  name: string;
  description: string | null;
  /** ISO 4217, e.g. "KES". */
  currency: string;
  status: ChamaStatus;
  createdBy: UUID;
  createdAt: ISOInstant;
  settings: ChamaSettings;
}

export interface LicenseView {
  licenseKey: string;
  plan: LicensePlan;
  state: LicenseState;
  validFrom: ISOInstant;
  validUntil: ISOInstant;
  /** Whole days until expiry; 0 once expired. */
  daysRemaining: number;
  maxMembers: number;
  features: LicenseFeature[];
  notes: string | null;
}

/** Platform admin: onboard a chama. */
export interface OnboardChamaRequest {
  name: string; // max 120
  /** 3-30 chars: letters, digits, single hyphens ("mokica", "umoja-2"). Permanent. */
  code: string;
  description?: string; // max 1000
  /** ISO 4217, default "KES". */
  currency?: string;
  /** Default STANDARD_SETTINGS. */
  settings?: ChamaSettingsRequest;
  admin: {
    email: string;
    phoneNumber: string;
    password: string; // password rules as RegisterRequest
    firstName: string;
    lastName: string;
  };
}

/** Every field optional; omitted fields stay unchanged. Changing `plan` resets maxMembers/features to the plan's defaults unless also given. */
export interface UpdateLicenseRequest {
  plan?: LicensePlan;
  validUntil?: ISOInstant;
  maxMembers?: number; // >= 1
  features?: LicenseFeature[];
  status?: LicenseStatus;
  notes?: string; // max 500
}

export interface PlatformOnboardChamaRequest {
  chama: OnboardChamaRequest;
  /** Starting licence instead of the 30-day TRIAL. */
  license?: UpdateLicenseRequest;
}

export interface OnboardingResponse {
  chama: ChamaResponse;
  license: LicenseView;
  admin: { userId: UUID; email: string };
}

export interface PlatformChamaView {
  chama: ChamaResponse;
  license: LicenseView;
}

/** Plan defaults (a platform admin can override per chama). */
export const PLAN_DEFAULTS: Record<LicensePlan, { maxMembers: number; features: LicenseFeature[] }> = {
  TRIAL: { maxMembers: 25, features: ['CONTRIBUTIONS', 'PENALTIES', 'EXPENSES', 'LOANS', 'INVESTMENTS', 'REPORTS', 'MPESA', 'SMS_NOTIFICATIONS'] },
  BASIC: { maxMembers: 30, features: ['CONTRIBUTIONS', 'PENALTIES', 'EXPENSES', 'REPORTS'] },
  STANDARD: { maxMembers: 100, features: ['CONTRIBUTIONS', 'PENALTIES', 'EXPENSES', 'REPORTS', 'LOANS', 'INVESTMENTS', 'SMS_NOTIFICATIONS'] },
  PREMIUM: { maxMembers: 1000, features: ['CONTRIBUTIONS', 'PENALTIES', 'EXPENSES', 'LOANS', 'INVESTMENTS', 'REPORTS', 'MPESA', 'SMS_NOTIFICATIONS'] },
};

// =====================================================================================================
// Members & roles  (/api/v1/members, /api/v1/roles)
// =====================================================================================================

/**
 * NOTE two ids: `membershipId` is used by the /members/{id}/... management endpoints;
 * `userId` is what every financial endpoint calls `memberUserId`.
 */
export interface MemberView {
  membershipId: UUID;
  chamaId: UUID;
  /** Assigned on approval; null while pending/rejected. */
  memberNumber: number | null;
  userId: UUID;
  email: string;
  firstName: string | null;
  lastName: string | null;
  phoneNumber: string | null;
  emailVerified: boolean;
  status: MembershipStatus;
  roles: ChamaRole[];
  appliedAt: ISOInstant;
  joinedAt: ISOInstant | null;
  suspensionReason: string | null;
}

/** GET /members/me. `permissions` is empty unless the membership is ACTIVE. */
export interface MyMembershipView {
  membership: MemberView;
  permissions: ChamaPermission[];
}

export interface InvitationView {
  email: string;
  chamaCode: string;
  invitedAt: ISOInstant;
}

export interface InviteMemberRequest {
  email: string;
}
export interface SuspendMemberRequest {
  reason: string; // max 500
}
/** MEMBER is always kept even if left out. */
export interface ChangeRolesRequest {
  roles: ChamaRole[];
}
export interface RolePermissionsRequest {
  permissions: ChamaPermission[];
}
/** GET /roles: role name -> its permissions in this chama. */
export type RolePermissionsMap = Record<ChamaRole, ChamaPermission[]>;

// =====================================================================================================
// Contributions  (/api/v1/contributions)
// =====================================================================================================

export interface AllocationRequest {
  productId: UUID;
  amountMinor: Minor; // > 0
}

export interface RecordPaymentRequest {
  memberUserId: UUID;
  amountMinor: Minor; // > 0
  method: PaymentMethod;
  /** Required for MPESA (transaction code) and BANK (slip number). Unique per chama. Max 100. */
  reference?: string;
  /** Default today; not in the future. */
  paidOn?: ISODate;
  notes?: string; // max 300
  /** Optional split across savings products (1-20 items) adding up to amountMinor. Omit to auto-allocate. */
  allocations?: AllocationRequest[];
}

export interface ReversePaymentRequest {
  reason: string; // max 300
}

export interface AllocationView {
  productId: UUID;
  productCode: string;
  productName: string;
  amountMinor: Minor;
}

export interface PaymentView {
  id: UUID;
  memberUserId: UUID;
  memberName: string | null;
  memberNumber: number | null;
  amountMinor: Minor;
  currency: string;
  method: PaymentMethod;
  reference: string | null;
  paidOn: ISODate;
  notes: string | null;
  status: ContributionPaymentStatus;
  ledgerEntryId: UUID | null;
  recordedBy: UUID;
  recordedAt: ISOInstant;
  reversedBy: UUID | null;
  reversedAt: ISOInstant | null;
  reversalReason: string | null;
  allocations: AllocationView[];
}

export interface PeriodProductView {
  productId: UUID;
  productCode: string;
  productName: string;
  amountMinor: Minor;
}

export interface PeriodView {
  id: UUID;
  /** "2026-11" for MONTHLY, "2026-W45" for WEEKLY. */
  key: string;
  frequency: ContributionFrequency;
  startDate: ISODate;
  endDate: ISODate;
  dueDate: ISODate;
  /** What each member owes for the period across every product. */
  amountDueMinor: Minor;
  currency: string;
  products: PeriodProductView[];
}

export interface PeriodSummaryView {
  period: PeriodView;
  /** The due date has arrived. */
  due: boolean;
  expectedMinor: Minor;
  collectedMinor: Minor;
  members: number;
  membersPaid: number;
  membersLate: number;
}

export interface MemberPeriodView {
  memberUserId: UUID;
  memberName: string | null;
  memberNumber: number | null;
  amountDueMinor: Minor;
  paidMinor: Minor;
  status: PeriodPaymentStatus;
  late: boolean;
}

export interface PeriodDetailView {
  summary: PeriodSummaryView;
  members: MemberPeriodView[];
}

export interface ProductStandingView {
  productId: UUID;
  productCode: string;
  productName: string;
  totalPaidMinor: Minor;
  dueToDateMinor: Minor;
  arrearsMinor: Minor;
  advanceMinor: Minor;
}

export interface StandingView {
  memberUserId: UUID;
  memberName: string | null;
  memberNumber: number | null;
  currency: string;
  totalPaidMinor: Minor;
  dueToDateMinor: Minor;
  arrearsMinor: Minor;
  advanceMinor: Minor;
  /** paid - due-to-date: negative = in arrears, positive = credit. */
  balanceMinor: Minor;
  latePeriods: number;
  products: ProductStandingView[];
}

export interface MyPeriodView {
  period: PeriodView;
  amountDueMinor: Minor;
  paidMinor: Minor;
  status: PeriodPaymentStatus;
  due: boolean;
  late: boolean;
}

export interface MyContributionsView {
  standing: StandingView;
  periods: MyPeriodView[];
  recentPayments: PaymentView[];
}

export interface ContributionListParams extends PageParams {
  memberUserId?: UUID;
  from?: ISODate;
  to?: ISODate;
  status?: ContributionPaymentStatus;
}

// =====================================================================================================
// Penalties  (/api/v1/penalties)
// =====================================================================================================

export interface FineRequest {
  memberUserId: UUID;
  amountMinor: Minor; // > 0
  reason: string; // max 300
}

export interface PenaltyPaymentRequest {
  amountMinor: Minor; // > 0, at most what is outstanding
  method: PaymentMethod;
  reference?: string; // required for MPESA/BANK
  paidOn?: ISODate;
}

export interface WaiveRequest {
  reason: string; // max 300
}

export interface PenaltyView {
  id: UUID;
  memberUserId: UUID;
  memberName: string | null;
  memberNumber: number | null;
  kind: PenaltyKind;
  description: string;
  amountMinor: Minor;
  paidMinor: Minor;
  waivedMinor: Minor;
  outstandingMinor: Minor;
  currency: string;
  status: PenaltyStatus;
  assessedOn: ISODate;
  /** null for automatic late-contribution penalties. */
  assessedBy: UUID | null;
  waivedBy: UUID | null;
  waivedAt: ISOInstant | null;
  waiverReason: string | null;
}

export interface PenaltyPaymentView {
  id: UUID;
  amountMinor: Minor;
  method: PaymentMethod;
  reference: string | null;
  paidOn: ISODate;
  ledgerEntryId: UUID | null;
  recordedBy: UUID;
  recordedAt: ISOInstant;
}

export interface PenaltyDetailView {
  penalty: PenaltyView;
  payments: PenaltyPaymentView[];
}

export interface MyPenaltiesView {
  currency: string;
  outstandingMinor: Minor;
  penalties: PenaltyView[];
}

export interface PenaltyListParams extends PageParams {
  memberUserId?: UUID;
  status?: PenaltyStatus;
}

// =====================================================================================================
// Loans & loan products  (/api/v1/loans, /api/v1/loan-products)
// =====================================================================================================

export const LOAN_MAX_TERM_MONTHS = 36;

export interface ApplyLoanRequest {
  /** Omit for the default loan product. */
  productId?: UUID;
  principalMinor: Minor; // > 0
  termMonths: number; // 1-36, and <= the product's maxTermMonths
  purpose: string; // max 300
  /** Active members (not yourself), max 10. Needed only above the product's guarantor threshold. */
  guarantorUserIds?: UUID[];
}

export interface AddGuarantorRequest {
  guarantorUserId: UUID;
}
export interface GuaranteeResponseRequest {
  accept: boolean;
}
export interface LoanApproveRequest {
  note?: string; // max 300; whole body optional
}
export interface LoanRejectRequest {
  reason: string; // max 300
}
export interface DisburseRequest {
  method: PaymentMethod;
  reference?: string; // required for MPESA/BANK
  disbursedOn?: ISODate;
}
export interface RepaymentRequest {
  amountMinor: Minor; // > 0, at most outstanding
  method: PaymentMethod;
  reference?: string;
  paidOn?: ISODate;
}

export interface EligibilityView {
  eligible: boolean;
  /** Human-readable reasons the member cannot borrow; empty when eligible. Show them as a list. */
  reasons: string[];
  productId: UUID;
  productCode: string;
  productName: string;
  currency: string;
  /** Savings in products that back loans. */
  savingsMinor: Minor;
  /** Owed or applied for on loans of other products; deducted from the limit. */
  otherLoansMinor: Minor;
  maxLoanMinor: Minor;
  maxMultiplier: number;
  interestBasisPoints: BasisPoints;
  maxTermMonths: number;
  membershipMonths: number;
  minMembershipMonths: number;
  guarantorThresholdMinor: Minor;
  guarantorsRequired: number;
  approvalQuorum: number;
}

export interface LoanView {
  id: UUID;
  borrowerUserId: UUID;
  borrowerName: string | null;
  memberNumber: number | null;
  productId: UUID;
  productCode: string;
  productName: string;
  principalMinor: Minor;
  interestBasisPoints: BasisPoints;
  interestMinor: Minor;
  totalDueMinor: Minor;
  repaidMinor: Minor;
  /** 0 until disbursed. */
  outstandingMinor: Minor;
  /** Instalments past due and unpaid; 0 until disbursed. */
  arrearsMinor: Minor;
  currency: string;
  termMonths: number;
  purpose: string;
  status: LoanStatus;
  appliedOn: ISODate;
  requiredGuarantors: number;
  acceptedGuarantors: number;
  requiredApprovals: number;
  approvals: number;
  disbursedOn: ISODate | null;
  nextDueDate: ISODate | null;
  nextDueMinor: Minor;
  maturityDate: ISODate | null;
  rejectionReason: string | null;
}

export interface GuarantorView {
  userId: UUID;
  name: string | null;
  memberNumber: number | null;
  status: GuarantorStatus;
  requestedAt: ISOInstant;
  respondedAt: ISOInstant | null;
}

export interface LoanApprovalView {
  approverUserId: UUID;
  name: string | null;
  note: string | null;
  approvedAt: ISOInstant;
}

export interface InstalmentView {
  number: number;
  dueDate: ISODate;
  amountMinor: Minor;
  paidMinor: Minor;
  status: InstalmentStatus;
}

export interface LoanRepaymentView {
  id: UUID;
  amountMinor: Minor;
  method: PaymentMethod;
  reference: string | null;
  paidOn: ISODate;
  ledgerEntryId: UUID | null;
  recordedBy: UUID;
  recordedAt: ISOInstant;
}

export interface DisbursementView {
  disbursedOn: ISODate;
  disbursedBy: UUID;
  method: PaymentMethod;
  reference: string | null;
  ledgerEntryId: UUID | null;
}

export interface DecisionView {
  rejectedBy: UUID | null;
  rejectedAt: ISOInstant | null;
  reason: string | null;
  cancelledAt: ISOInstant | null;
}

export interface LoanDetailView {
  loan: LoanView;
  guarantors: GuarantorView[];
  approvals: LoanApprovalView[];
  /** null until disbursed. */
  disbursement: DisbursementView | null;
  /** null unless rejected or cancelled. */
  decision: DecisionView | null;
  /** Empty until disbursed. */
  schedule: InstalmentView[];
  repayments: LoanRepaymentView[];
}

export interface MyLoansView {
  currency: string;
  outstandingMinor: Minor;
  arrearsMinor: Minor;
  /** Newest first. */
  loans: LoanDetailView[];
}

export interface GuaranteeView {
  loan: LoanView;
  myStatus: GuarantorStatus;
}

export interface LoanListParams extends PageParams {
  memberUserId?: UUID;
  status?: LoanStatus;
}

export interface LoanProductRequest {
  /** Max 10. Required on create, ignored on update. */
  code?: string;
  name: string; // max 60
  interestBasisPoints: BasisPoints; // 0-10000
  maxTermMonths: number; // 1-36
  maxMultiplier: number; // 1-10
  minMembershipMonths: number; // 0-60
  guarantorThresholdMinor: Minor; // >= 0
  guarantorsRequired: number; // 0-5
}

export interface LoanProductView {
  id: UUID;
  code: string;
  name: string;
  currency: string;
  interestBasisPoints: BasisPoints;
  maxTermMonths: number;
  maxMultiplier: number;
  minMembershipMonths: number;
  guarantorThresholdMinor: Minor;
  guarantorsRequired: number;
  /** The default product mirrors the chama's loan settings; only its name is editable. */
  defaultProduct: boolean;
  active: boolean;
  createdAt: ISOInstant;
  updatedAt: ISOInstant | null;
}

// =====================================================================================================
// Expenses  (/api/v1/expenses)
// =====================================================================================================

export interface ExpenseRequest {
  amountMinor: Minor; // > 0
  payee: string; // max 120
  description: string; // max 300
  /** An active EXPENSE account code; default "5000" Operating expenses. */
  accountCode?: string;
  /** An ACTIVE investment the expense is for (its running costs). */
  investmentId?: UUID;
  /** Default today; not in the future. */
  incurredOn?: ISODate;
}

export interface ExpenseApproveRequest {
  note?: string;
}
export interface ExpenseRejectRequest {
  reason: string;
}
export interface ExpensePayRequest {
  method: PaymentMethod;
  reference?: string;
  paidOn?: ISODate;
}

export interface ExpenseApprovalView {
  approverUserId: UUID;
  name: string | null;
  note: string | null;
  approvedAt: ISOInstant;
}

export interface ExpenseView {
  id: UUID;
  amountMinor: Minor;
  currency: string;
  payee: string;
  description: string;
  accountCode: string;
  accountName: string;
  investmentId: UUID | null;
  investmentName: string | null;
  incurredOn: ISODate;
  status: ExpenseStatus;
  requestedBy: UUID;
  requestedByName: string | null;
  requiredApprovals: number;
  approvals: ExpenseApprovalView[];
  rejectedBy: UUID | null;
  rejectedAt: ISOInstant | null;
  rejectionReason: string | null;
  cancelledAt: ISOInstant | null;
  paidOn: ISODate | null;
  paidBy: UUID | null;
  paymentMethod: PaymentMethod | null;
  paymentReference: string | null;
  ledgerEntryId: UUID | null;
}

export interface ExpenseAccountTotal {
  accountCode: string;
  accountName: string;
  totalMinor: Minor;
  count: number;
}

export interface ExpenseSummaryView {
  currency: string;
  from: ISODate;
  to: ISODate;
  paidMinor: Minor;
  paidCount: number;
  /** Pending approval or approved but unpaid. */
  awaitingPaymentMinor: Minor;
  byAccount: ExpenseAccountTotal[];
}

export interface ExpenseListParams extends PageParams {
  status?: ExpenseStatus;
  investmentId?: UUID;
  /** Filters on incurredOn. */
  from?: ISODate;
  to?: ISODate;
}

// =====================================================================================================
// Investments  (/api/v1/investments)
// =====================================================================================================

export interface ProposeInvestmentRequest {
  name: string; // max 120
  type: InvestmentType;
  institution?: string; // max 120
  description?: string; // max 300
  amountMinor: Minor; // > 0
  /** Expected yearly return, >= 0. */
  expectedReturnBasisPoints?: BasisPoints;
  /** For fixed-term investments; in the future. */
  maturityDate?: ISODate;
}

export interface InvestmentApproveRequest {
  note?: string;
}
export interface InvestmentRejectRequest {
  reason: string;
}
/** Funds exactly the approved amount. */
export interface FundRequest {
  method: PaymentMethod;
  reference?: string;
  fundedOn?: ISODate;
}
export interface DepositRequest {
  amountMinor: Minor;
  method: PaymentMethod;
  reference?: string;
  depositedOn?: ISODate;
  note?: string;
}
export interface IncomeRequest {
  amountMinor: Minor;
  method: IncomeMethod;
  reference?: string;
  receivedOn?: ISODate;
  note?: string;
}
/** Less than the book value; close the investment to take everything out. */
export interface WithdrawalRequest {
  amountMinor: Minor;
  method: PaymentMethod;
  reference?: string;
  withdrawnOn?: ISODate;
  note?: string;
}
export interface CloseInvestmentRequest {
  /** Everything that came back, >= 0. */
  proceedsMinor: Minor;
  method: PaymentMethod;
  reference?: string;
  closedOn?: ISODate;
  note?: string;
}
export interface ValuationRequest {
  valueMinor: Minor; // >= 0
  valuedOn?: ISODate;
  note?: string;
}

export interface InvestmentApprovalView {
  approverUserId: UUID;
  name: string | null;
  note: string | null;
  approvedAt: ISOInstant;
}

export interface InvestmentPerformance {
  investedMinor: Minor;
  incomeMinor: Minor;
  expensesMinor: Minor;
  netIncomeMinor: Minor;
  /** Realised gain once closed; otherwise latest valuation - book value. */
  gainMinor: Minor;
  gainRealised: boolean;
  /** Net income + gain: the profit (negative = loss). */
  totalReturnMinor: Minor;
  returnBasisPoints: BasisPoints;
  /** Simple yearly return; null when held under 30 days. */
  annualisedReturnBasisPoints: BasisPoints | null;
  daysHeld: number;
  profitable: boolean;
}

export interface InvestmentView {
  id: UUID;
  name: string;
  type: InvestmentType;
  institution: string | null;
  description: string | null;
  amountMinor: Minor;
  currency: string;
  expectedReturnBasisPoints: BasisPoints | null;
  maturityDate: ISODate | null;
  status: InvestmentStatus;
  proposedBy: UUID;
  proposedByName: string | null;
  requiredApprovals: number;
  approvals: InvestmentApprovalView[];
  rejectedBy: UUID | null;
  rejectedAt: ISOInstant | null;
  rejectionReason: string | null;
  cancelledAt: ISOInstant | null;
  fundedOn: ISODate | null;
  /** Put in + reinvested income - withdrawals. */
  bookValueMinor: Minor;
  incomeMinor: Minor;
  withdrawnMinor: Minor;
  depositedMinor: Minor;
  /** Latest valuation, or book value if never valued. */
  marketValueMinor: Minor;
  latestValuationMinor: Minor | null;
  latestValuationOn: ISODate | null;
  unrealisedGainMinor: Minor | null;
  closedOn: ISODate | null;
  closingProceedsMinor: Minor | null;
  realisedGainMinor: Minor | null;
  /** null before funding. */
  performance: InvestmentPerformance | null;
}

export interface InvestmentTransactionView {
  id: UUID;
  kind: InvestmentTransactionKind;
  amountMinor: Minor;
  method: IncomeMethod;
  reference: string | null;
  date: ISODate;
  note: string | null;
  ledgerEntryId: UUID | null;
  recordedBy: UUID;
  recordedAt: ISOInstant;
}

export interface ValuationView {
  id: UUID;
  valueMinor: Minor;
  valuedOn: ISODate;
  note: string | null;
  recordedBy: UUID;
  recordedAt: ISOInstant;
}

export interface InvestmentDetailView {
  investment: InvestmentView;
  transactions: InvestmentTransactionView[];
  valuations: ValuationView[];
}

export interface InvestmentTypeTotal {
  type: InvestmentType;
  count: number;
  bookValueMinor: Minor;
  marketValueMinor: Minor;
}

export interface PortfolioView {
  currency: string;
  activeCount: number;
  bookValueMinor: Minor;
  marketValueMinor: Minor;
  investedMinor: Minor;
  incomeMinor: Minor;
  expensesMinor: Minor;
  realisedGainMinor: Minor;
  unrealisedGainMinor: Minor;
  totalReturnMinor: Minor;
  returnBasisPoints: BasisPoints;
  byType: InvestmentTypeTotal[];
  maturingWithin30Days: InvestmentView[];
}

export interface InvestmentListParams extends PageParams {
  status?: InvestmentStatus;
  type?: InvestmentType;
}

// =====================================================================================================
// Ledger  (/api/v1/ledger)
// =====================================================================================================

export interface LedgerAccountView {
  id: UUID;
  code: string;
  name: string;
  type: AccountType;
  /** Set on built-in accounts (cannot be deactivated); null on accounts the chama added. */
  systemKey: LedgerAccountKey | null;
  currency: string;
  /** Per-member account: every posting line needs memberUserId (savings, loans receivable, penalties receivable, savings products). */
  memberLedger: boolean;
  active: boolean;
  /** On the account's normal side: positive asset = held, positive liability = owed. */
  balanceMinor: Minor;
}

export interface CreateLedgerAccountRequest {
  code: string; // max 10, unique in the chama
  name: string; // max 100
  type: AccountType;
}

export interface AccountStatusRequest {
  active: boolean;
}

/** Give exactly one of debitMinor / creditMinor (> 0). */
export interface ManualLineRequest {
  accountCode: string;
  debitMinor?: Minor;
  creditMinor?: Minor;
  /** Required on memberLedger accounts, forbidden on others. */
  memberUserId?: UUID;
  memo?: string; // max 200
}

/** Debits must equal credits. 2-200 lines. */
export interface ManualEntryRequest {
  /** Default today; not in the future. */
  entryDate?: ISODate;
  description: string; // max 500
  reference?: string; // max 100
  lines: ManualLineRequest[];
}

export interface ReverseEntryRequest {
  reason: string; // max 300
}

export interface LedgerLineView {
  accountId: UUID;
  accountCode: string;
  accountName: string;
  debitMinor: Minor;
  creditMinor: Minor;
  memberUserId: UUID | null;
  memo: string | null;
}

export interface EntryView {
  id: UUID;
  /** Sequential per chama; show as "#123". */
  number: number;
  entryDate: ISODate;
  description: string;
  reference: string | null;
  sourceType: LedgerSourceType;
  sourceId: string | null;
  /** Set on a reversal: the entry it cancels. */
  reversesEntryId: UUID | null;
  /** Set on a reversed entry: its reversal. */
  reversedByEntryId: UUID | null;
  recordedBy: UUID | null;
  recordedAt: ISOInstant;
  totalMinor: Minor;
  lines: LedgerLineView[];
}

export interface LedgerStatementLine {
  entryId: UUID;
  entryNumber: number;
  entryDate: ISODate;
  description: string;
  reference: string | null;
  sourceType: LedgerSourceType;
  debitMinor: Minor;
  creditMinor: Minor;
  /** Running balance after this line. */
  balanceMinor: Minor;
  memberUserId: UUID | null;
  memo: string | null;
}

export interface LedgerStatementView {
  account: LedgerAccountView;
  memberUserId: UUID | null;
  from: ISODate | null;
  to: ISODate | null;
  openingBalanceMinor: Minor;
  closingBalanceMinor: Minor;
  lines: LedgerStatementLine[];
}

export interface TrialBalanceRow {
  accountId: UUID;
  code: string;
  name: string;
  type: AccountType;
  debitMinor: Minor;
  creditMinor: Minor;
}

export interface TrialBalanceView {
  asOf: ISODate;
  currency: string;
  rows: TrialBalanceRow[];
  totalDebitsMinor: Minor;
  totalCreditsMinor: Minor;
  balanced: boolean;
}

export interface MemberBalancesView {
  memberUserId: UUID;
  currency: string;
  savingsMinor: Minor;
  loansOutstandingMinor: Minor;
  penaltiesOutstandingMinor: Minor;
}

export interface LedgerEntryListParams extends PageParams {
  from?: ISODate;
  to?: ISODate;
  sourceType?: LedgerSourceType;
}

// =====================================================================================================
// Savings products & member accounts  (/api/v1/savings-products, /api/v1/member-accounts)
// =====================================================================================================

export interface CreateSavingsProductRequest {
  code: string; // max 10, unique
  name: string; // max 60
  kind: SavingsProductKind;
  /** Optional; next free code for the kind (2010, 2020, ... or 3100 for SHARE_CAPITAL). */
  ledgerAccountCode?: string;
  /** Owed per contribution period; 0 allowed (voluntary product). */
  contributionMinor: Minor;
  /** 1-99, default 10. Lower is paid first; Deposits (default product) is 50. */
  allocationPriority?: number;
  mandatory: boolean;
  /** Default from the kind when omitted. */
  countsTowardLoanLimit?: boolean;
  refundableOnExit?: boolean;
  earnsDividend?: boolean;
}

/** Flags left out keep their current value. */
export interface UpdateSavingsProductRequest {
  name: string;
  contributionMinor: Minor;
  allocationPriority: number;
  mandatory: boolean;
  countsTowardLoanLimit?: boolean;
  refundableOnExit?: boolean;
  earnsDividend?: boolean;
}

export interface SavingsProductView {
  id: UUID;
  code: string;
  name: string;
  kind: SavingsProductKind;
  ledgerAccountCode: string;
  currency: string;
  contributionMinor: Minor;
  allocationPriority: number;
  mandatory: boolean;
  countsTowardLoanLimit: boolean;
  refundableOnExit: boolean;
  earnsDividend: boolean;
  /** "Deposits": follows the chama's contribution setting, always active and mandatory. */
  defaultProduct: boolean;
  active: boolean;
  /** Members with an open account in it. */
  members: number;
  /** Total across members. */
  balanceMinor: Minor;
  createdAt: ISOInstant;
  updatedAt: ISOInstant | null;
}

export interface MemberAccountView {
  id: UUID;
  accountNumber: string;
  memberUserId: UUID;
  memberName: string | null;
  memberNumber: number | null;
  productId: UUID;
  productCode: string;
  productName: string;
  productKind: SavingsProductKind;
  status: MemberAccountStatus;
  openedOn: ISODate;
  closedOn: ISODate | null;
  closureReason: string | null;
  currency: string;
  balanceMinor: Minor;
}

export interface MyAccountsView {
  currency: string;
  totalBalanceMinor: Minor;
  accounts: MemberAccountView[];
}

export interface MemberAccountStatementLine {
  entryId: UUID;
  entryNumber: number;
  entryDate: ISODate;
  description: string;
  reference: string | null;
  sourceType: LedgerSourceType;
  debitMinor: Minor;
  creditMinor: Minor;
  balanceMinor: Minor;
  memo: string | null;
}

/** Oldest first. */
export interface MemberAccountStatementView {
  account: MemberAccountView;
  from: ISODate | null;
  to: ISODate | null;
  openingBalanceMinor: Minor;
  closingBalanceMinor: Minor;
  lines: MemberAccountStatementLine[];
}

export interface OpenAccountRequest {
  memberUserId: UUID;
  productId: UUID;
}
export interface CloseAccountRequest {
  reason: string; // max 300
}

export interface MemberAccountListParams {
  memberUserId?: UUID;
  productId?: UUID;
  status?: MemberAccountStatus;
}

// =====================================================================================================
// Dashboards  (/api/v1/dashboard)
// Sections are null when the chama's licence does not include the feature.
// =====================================================================================================

export interface MembersSection {
  active: number;
  suspended: number;
  pendingApplications: number;
}
export interface CashSection {
  cashOnHandMinor: Minor;
  bankMinor: Minor;
  mobileMoneyMinor: Minor;
  totalMinor: Minor;
}
export interface ProductBalance {
  productId: UUID;
  code: string;
  name: string;
  balanceMinor: Minor;
}
export interface SavingsSection {
  totalMinor: Minor;
  products: ProductBalance[];
}
export interface ContributionsSection {
  currentPeriod: string | null;
  dueDate: ISODate | null;
  expectedMinor: Minor;
  collectedMinor: Minor;
  /** Collected per 10,000 expected. */
  collectionRateBasisPoints: BasisPoints;
  membersPaid: number;
  membersLate: number;
  arrearsMinor: Minor;
  membersInArrears: number;
}
export interface LoansSection {
  activeLoans: number;
  outstandingMinor: Minor;
  arrearsMinor: Minor;
  loansInArrears: number;
  /** Portfolio at risk > 30 days, per 10,000 outstanding. */
  par30BasisPoints: BasisPoints;
  awaitingApproval: number;
  awaitingDisbursement: number;
}
export interface InvestmentsSection {
  active: number;
  investedMinor: Minor;
  marketValueMinor: Minor;
  incomeMinor: Minor;
}
export interface PerformanceSection {
  from: ISODate;
  to: ISODate;
  incomeMinor: Minor;
  expensesMinor: Minor;
  /** Negative = deficit. */
  surplusMinor: Minor;
}
/** Work queue for officials; individual counts are null where the feature is not licensed. */
export interface PendingSection {
  membershipApplications: number;
  loansAwaitingApproval: number | null;
  loansAwaitingDisbursement: number | null;
  expensesAwaitingApproval: number | null;
  expensesAwaitingPayment: number | null;
  investmentsAwaitingApproval: number | null;
}
export interface EntrySummary {
  id: UUID;
  number: number;
  entryDate: ISODate;
  description: string;
  reference: string | null;
  sourceType: LedgerSourceType;
  totalMinor: Minor;
  reversal: boolean;
}

export interface ChamaDashboardView {
  chamaName: string;
  asOf: ISODate;
  currency: string;
  features: LicenseFeature[];
  members: MembersSection;
  cash: CashSection;
  savings: SavingsSection | null;
  contributions: ContributionsSection | null;
  loans: LoansSection | null;
  penaltiesOutstandingMinor: Minor | null;
  investments: InvestmentsSection | null;
  thisMonth: PerformanceSection;
  yearToDate: PerformanceSection;
  pending: PendingSection;
  recentEntries: EntrySummary[];
  generatedAt: ISOInstant;
}

export interface MyAccount {
  accountId: UUID;
  accountNumber: string;
  productCode: string;
  productName: string;
  active: boolean;
  balanceMinor: Minor;
}
export interface MySavingsSection {
  totalMinor: Minor;
  accounts: MyAccount[];
}
export interface MyContributionsSection {
  totalPaidMinor: Minor;
  arrearsMinor: Minor;
  advanceMinor: Minor;
  latePeriods: number;
  currentPeriod: string | null;
  currentPeriodStatus: PeriodPaymentStatus | null;
}
export interface MyLoansSection {
  activeLoans: number;
  inProgress: number;
  outstandingMinor: Minor;
  arrearsMinor: Minor;
  nextDueDate: ISODate | null;
  nextDueMinor: Minor;
  /** Guarantee requests waiting for this member's answer. */
  guaranteeRequests: number;
}

export interface MemberDashboardView {
  chamaName: string;
  memberUserId: UUID;
  memberName: string | null;
  memberNumber: number | null;
  currency: string;
  features: LicenseFeature[];
  savings: MySavingsSection | null;
  contributions: MyContributionsSection | null;
  loans: MyLoansSection | null;
  penaltiesOutstandingMinor: Minor | null;
  generatedAt: ISOInstant;
}

// =====================================================================================================
// Reports  (/api/v1/reports)
// =====================================================================================================

export interface MemberRef {
  userId: UUID;
  name: string | null;
  memberNumber: number | null;
}

/** `code` is null on a computed line (e.g. "Surplus to date"). */
export interface ReportLine {
  code: string | null;
  name: string;
  amountMinor: Minor;
}
export interface ReportSection {
  lines: ReportLine[];
  totalMinor: Minor;
}

export interface FinancialPositionView {
  chamaName: string;
  asOf: ISODate;
  currency: string;
  assets: ReportSection;
  liabilities: ReportSection;
  equity: ReportSection;
  liabilitiesAndEquityMinor: Minor;
  balanced: boolean;
  generatedAt: ISOInstant;
}

export interface IncomeStatementView {
  chamaName: string;
  from: ISODate;
  to: ISODate;
  currency: string;
  income: ReportSection;
  expenses: ReportSection;
  surplusMinor: Minor;
  generatedAt: ISOInstant;
}

export interface CashLineView {
  date: ISODate;
  entryNumber: number;
  description: string;
  reference: string | null;
  sourceType: LedgerSourceType;
  receiptMinor: Minor;
  paymentMinor: Minor;
  balanceMinor: Minor;
}
export interface CashAccountView {
  code: string;
  name: string;
  openingBalanceMinor: Minor;
  receiptsMinor: Minor;
  paymentsMinor: Minor;
  closingBalanceMinor: Minor;
  lines: CashLineView[];
}
export interface CashBookView {
  chamaName: string;
  from: ISODate;
  to: ISODate;
  currency: string;
  accounts: CashAccountView[];
  openingBalanceMinor: Minor;
  receiptsMinor: Minor;
  paymentsMinor: Minor;
  closingBalanceMinor: Minor;
  generatedAt: ISOInstant;
}

export interface PeriodColumn {
  periodId: UUID;
  key: string;
  dueDate: ISODate;
  due: boolean;
  expectedMinor: Minor;
  collectedMinor: Minor;
  members: number;
  membersPaid: number;
  membersLate: number;
}
export interface ContributionCell {
  dueMinor: Minor;
  paidMinor: Minor;
  status: PeriodPaymentStatus;
  late: boolean;
}
export interface StandingSnapshot {
  totalPaidMinor: Minor;
  dueToDateMinor: Minor;
  arrearsMinor: Minor;
  advanceMinor: Minor;
  latePeriods: number;
  latestPeriod: string | null;
  latestPeriodStatus: PeriodPaymentStatus | null;
}
export interface MemberContributionRow {
  member: MemberRef;
  standing: StandingSnapshot;
  /** Same order as `periods`; null where the member owed nothing. */
  cells: (ContributionCell | null)[];
}
export interface ContributionReportView {
  chamaName: string;
  currency: string;
  /** Oldest first (columns). */
  periods: PeriodColumn[];
  members: MemberContributionRow[];
  expectedMinor: Minor;
  collectedMinor: Minor;
  arrearsMinor: Minor;
  generatedAt: ISOInstant;
}

export interface LoanSnapshot {
  id: UUID;
  borrowerUserId: UUID;
  productId: UUID;
  productCode: string;
  productName: string;
  status: LoanStatus;
  currency: string;
  principalMinor: Minor;
  interestMinor: Minor;
  totalDueMinor: Minor;
  repaidMinor: Minor;
  outstandingMinor: Minor;
  arrearsMinor: Minor;
  daysInArrears: number;
  termMonths: number;
  purpose: string;
  appliedOn: ISODate;
  disbursedOn: ISODate | null;
  maturityDate: ISODate | null;
  nextDueDate: ISODate | null;
  nextDueMinor: Minor;
}
export interface BucketTotal {
  bucket: LoanAgingBucket;
  /** e.g. "1-30 days overdue". */
  label: string;
  loans: number;
  outstandingMinor: Minor;
  arrearsMinor: Minor;
}
export interface LoanRow {
  borrower: MemberRef;
  loan: LoanSnapshot;
  bucket: LoanAgingBucket;
}
export interface LoanPortfolioView {
  chamaName: string;
  asOf: ISODate;
  currency: string;
  activeLoans: number;
  outstandingMinor: Minor;
  arrearsMinor: Minor;
  loansInArrears: number;
  par30BasisPoints: BasisPoints;
  aging: BucketTotal[];
  awaitingApproval: number;
  awaitingDisbursement: number;
  pipelineMinor: Minor;
  /** Most overdue first. */
  loans: LoanRow[];
  generatedAt: ISOInstant;
}

export interface ProductColumn {
  productId: UUID;
  code: string;
  name: string;
  kind: SavingsProductKind;
  active: boolean;
  totalMinor: Minor;
}
export interface SavingsRow {
  member: MemberRef;
  /** Same order as `products`. */
  balances: Minor[];
  totalMinor: Minor;
}
export interface SavingsRegisterView {
  chamaName: string;
  currency: string;
  products: ProductColumn[];
  members: SavingsRow[];
  totalMinor: Minor;
  generatedAt: ISOInstant;
}

export interface ReportStatementLine {
  date: ISODate;
  entryNumber: number;
  description: string;
  reference: string | null;
  sourceType: LedgerSourceType;
  debitMinor: Minor;
  creditMinor: Minor;
  balanceMinor: Minor;
}
export interface AccountStatementView {
  accountNumber: string | null;
  code: string;
  name: string;
  openingBalanceMinor: Minor;
  closingBalanceMinor: Minor;
  lines: ReportStatementLine[];
}
export interface MemberStatementView {
  chamaName: string;
  member: MemberRef;
  from: ISODate | null;
  to: ISODate | null;
  currency: string;
  savingsMinor: Minor;
  loansOutstandingMinor: Minor;
  penaltiesOutstandingMinor: Minor;
  /** Savings - loans - penalties owed. */
  netPositionMinor: Minor;
  contributions: StandingSnapshot | null;
  savings: AccountStatementView[];
  loans: LoanSnapshot[];
  penalties: AccountStatementView | null;
  generatedAt: ISOInstant;
}

export interface DateRangeParams {
  from?: ISODate;
  to?: ISODate;
}

// =====================================================================================================
// Audit log  (/api/v1/audit-log)
// =====================================================================================================

export interface AuditLogEntry {
  id: UUID;
  occurredAt: ISOInstant;
  /** null for system jobs. */
  actorUserId: UUID | null;
  /** e.g. "LOAN_APPROVED". See KNOWN_AUDIT_ACTIONS. */
  action: string;
  /** e.g. "MEMBERSHIP", "LOAN", "LEDGER_ACCOUNT". */
  targetType: string;
  targetId: string | null;
  /** Free-form facts (before/after values, amounts). Render as key/value pairs. */
  details: Record<string, unknown>;
  ipAddress: string | null;
}

export interface AuditLogParams extends PageParams {
  /** Exact match. */
  action?: string;
}

/** Action names the backend writes today (for the filter dropdown; the list is open-ended). */
export const KNOWN_AUDIT_ACTIONS = [
  'CHAMA_CREATED', 'CHAMA_SETTINGS_CHANGED', 'LICENSE_ISSUED', 'LICENSE_UPDATED',
  'MEMBER_APPLIED', 'MEMBER_INVITED', 'MEMBER_APPROVED', 'MEMBER_REJECTED', 'MEMBER_SUSPENDED', 'MEMBER_REACTIVATED',
  'MEMBER_ROLES_CHANGED', 'MEMBER_FOUNDER_ENROLLED', 'MEMBER_IMPORTED', 'ROLE_PERMISSIONS_CHANGED',
  'CONTRIBUTION_RECORDED', 'CONTRIBUTION_REVERSED',
  'PENALTY_ASSESSED', 'PENALTY_FINED', 'PENALTY_PAID', 'PENALTY_WAIVED', 'PENALTY_IMPORTED',
  'LOAN_APPLIED', 'LOAN_GUARANTOR_ADDED', 'LOAN_GUARANTEE_ACCEPTED', 'LOAN_APPROVAL_GIVEN', 'LOAN_APPROVED',
  'LOAN_REJECTED', 'LOAN_CANCELLED', 'LOAN_DISBURSED', 'LOAN_REPAYMENT_RECORDED', 'LOAN_REPAID', 'LOAN_IMPORTED',
  'LOAN_PRODUCT_CREATED', 'LOAN_PRODUCT_UPDATED', 'LOAN_PRODUCT_ACTIVATED', 'LOAN_PRODUCT_DEACTIVATED',
  'EXPENSE_REQUESTED', 'EXPENSE_APPROVAL_GIVEN', 'EXPENSE_APPROVED', 'EXPENSE_REJECTED', 'EXPENSE_CANCELLED', 'EXPENSE_PAID',
  'INVESTMENT_PROPOSED', 'INVESTMENT_APPROVAL_GIVEN', 'INVESTMENT_APPROVED', 'INVESTMENT_REJECTED', 'INVESTMENT_CANCELLED',
  'INVESTMENT_FUNDED', 'INVESTMENT_DEPOSIT', 'INVESTMENT_INCOME_RECORDED', 'INVESTMENT_WITHDRAWAL', 'INVESTMENT_VALUED',
  'INVESTMENT_CLOSED', 'INVESTMENT_IMPORTED',
  'LEDGER_ACCOUNT_CREATED', 'LEDGER_ACCOUNT_ACTIVATED', 'LEDGER_ACCOUNT_DEACTIVATED', 'LEDGER_ENTRY_POSTED', 'LEDGER_ENTRY_REVERSED',
  'SAVINGS_PRODUCT_CREATED', 'SAVINGS_PRODUCT_UPDATED', 'SAVINGS_PRODUCT_ACTIVATED', 'SAVINGS_PRODUCT_DEACTIVATED',
  'MEMBER_ACCOUNT_OPENED', 'MEMBER_ACCOUNT_CLOSED', 'MEMBER_ACCOUNT_REOPENED',
  'OPENING_BALANCES_IMPORTED',
] as const;

// =====================================================================================================
// Opening-balance import  (/api/v1/imports/opening-balances)
// =====================================================================================================

export interface ImportSummaryView {
  currency: string;
  newMembers: number;
  existingMembers: number;
  savingsMinor: Minor;
  loans: number;
  loansOutstandingMinor: Minor;
  penaltiesMinor: Minor;
  cashMinor: Minor;
  bankMinor: Minor;
  mobileMoneyMinor: Minor;
  investments: number;
  investmentsMinor: Minor;
  /** What the chama owns less what it owes members (negative when savings exceed holdings). */
  retainedSurplusMinor: Minor;
}

export interface ImportProblemView {
  severity: ImportProblemSeverity;
  /** "Members" | "Loans" | "Accounts" | "Investments", or null for the whole file. */
  sheet: string | null;
  /** Excel row number (header is row 1), or null. */
  row: number | null;
  /** Column heading, or null. */
  column: string | null;
  message: string;
}

export interface ImportPreviewView {
  /** No errors (warnings do not block). */
  importable: boolean;
  asOf: ISODate;
  errors: number;
  warnings: number;
  summary: ImportSummaryView | null;
  problems: ImportProblemView[];
}

export interface ImportView {
  id: UUID;
  asOf: ISODate;
  fileName: string | null;
  importedBy: UUID;
  importedAt: ISOInstant;
  /** The opening-balance journal entry; null if no money was brought forward. */
  ledgerEntryId: UUID | null;
  summary: ImportSummaryView;
  warnings: ImportProblemView[];
}

// =====================================================================================================
// Error codes
// =====================================================================================================

export type ErrorCode =
  // generic / transport
  | 'VALIDATION_FAILED' | 'MALFORMED_REQUEST' | 'INVALID_PARAMETER' | 'MISSING_PARAMETER' | 'BAD_REQUEST'
  | 'UNAUTHENTICATED' | 'ACCESS_DENIED' | 'NOT_FOUND' | 'METHOD_NOT_ALLOWED' | 'NOT_ACCEPTABLE'
  | 'CONFLICT' | 'CONCURRENT_MODIFICATION' | 'DATA_CONFLICT' | 'PAYLOAD_TOO_LARGE' | 'UNSUPPORTED_MEDIA_TYPE'
  | 'BUSINESS_RULE_VIOLATION' | 'TOO_MANY_REQUESTS' | 'INTERNAL_ERROR' | 'SERVICE_UNAVAILABLE'
  // tenancy
  | 'TENANT_REQUIRED' | 'TENANT_NOT_FOUND' | 'INVALID_PHONE_NUMBER'
  // auth
  | 'AUTH_INVALID_ACCESS_TOKEN' | 'AUTH_INVALID_TOKEN' | 'AUTH_INVALID_CREDENTIALS' | 'AUTH_ACCOUNT_LOCKED'
  | 'AUTH_ACCOUNT_DISABLED' | 'AUTH_EMAIL_NOT_VERIFIED' | 'AUTH_LINK_INVALID' | 'AUTH_CURRENT_PASSWORD_INCORRECT'
  | 'AUTH_USER_ALREADY_EXISTS' | 'AUTH_USER_NOT_FOUND' | 'AUTH_WEAK_PASSWORD' | 'AUTH_PASSWORD_TOO_LONG'
  | 'AUTH_INVALID_EMAIL' | 'AUTH_INVALID_FIRSTNAME' | 'AUTH_INVALID_LASTNAME'
  // chama & licence
  | 'CHAMA_NOT_FOUND' | 'CHAMA_INVALID_SETTINGS' | 'CHAMA_CODE_TAKEN' | 'CHAMA_INVALID_CODE' | 'CHAMA_INVALID_NAME'
  | 'CHAMA_INVALID_DESCRIPTION' | 'LICENSE_EXPIRED' | 'LICENSE_SUSPENDED' | 'LICENSE_FEATURE_NOT_INCLUDED'
  | 'LICENSE_NOT_FOUND' | 'LICENSE_MEMBER_LIMIT_REACHED' | 'LICENSE_INVALID_MEMBER_LIMIT' | 'LICENSE_INVALID_NOTES'
  | 'LICENSE_INVALID_PERIOD'
  // members
  | 'MEMBER_NOT_FOUND' | 'MEMBER_ALREADY_EXISTS' | 'MEMBER_SELF_MANAGEMENT' | 'MEMBER_LAST_CHAIRPERSON'
  | 'MEMBER_INVALID_STATE' | 'ROLE_PERMISSIONS_LOCKOUT'
  // contributions
  | 'CONTRIBUTION_INVALID_PAYMENT' | 'CONTRIBUTION_MEMBER_NOT_FOUND' | 'CONTRIBUTION_SELF_RECORDING'
  | 'CONTRIBUTION_DUPLICATE_REFERENCE' | 'CONTRIBUTION_NOT_FOUND' | 'CONTRIBUTION_ALREADY_REVERSED'
  | 'CONTRIBUTION_PERIOD_NOT_FOUND'
  // penalties
  | 'PENALTY_INVALID' | 'PENALTY_NOT_FOUND' | 'PENALTY_CLOSED' | 'PENALTY_SELF_HANDLING'
  | 'PENALTY_MEMBER_NOT_FOUND' | 'PENALTY_DUPLICATE_REFERENCE'
  // loans
  | 'LOAN_INVALID' | 'LOAN_NOT_ELIGIBLE' | 'LOAN_NOT_FOUND' | 'LOAN_WRONG_STATUS' | 'LOAN_SELF_HANDLING'
  | 'LOAN_GUARANTOR_CANNOT_APPROVE' | 'LOAN_ALREADY_APPROVED' | 'LOAN_NOT_BORROWER' | 'LOAN_NOT_GUARANTOR'
  | 'LOAN_GUARANTOR_RESPONDED' | 'LOAN_MEMBER_NOT_FOUND' | 'LOAN_INSUFFICIENT_FUNDS' | 'LOAN_DUPLICATE_REFERENCE'
  | 'LOAN_PRODUCT_INVALID' | 'LOAN_PRODUCT_NOT_FOUND' | 'LOAN_PRODUCT_CODE_TAKEN' | 'LOAN_PRODUCT_DEFAULT_RULE'
  | 'LOAN_PRODUCT_INACTIVE'
  // expenses
  | 'EXPENSE_INVALID' | 'EXPENSE_ACCOUNT_INVALID' | 'EXPENSE_INVESTMENT_INVALID' | 'EXPENSE_NOT_FOUND'
  | 'EXPENSE_WRONG_STATUS' | 'EXPENSE_SELF_APPROVAL' | 'EXPENSE_ALREADY_APPROVED' | 'EXPENSE_NOT_REQUESTER'
  | 'EXPENSE_INSUFFICIENT_FUNDS' | 'EXPENSE_DUPLICATE_REFERENCE'
  // investments
  | 'INVESTMENT_INVALID' | 'INVESTMENT_NOT_FOUND' | 'INVESTMENT_WRONG_STATUS' | 'INVESTMENT_SELF_APPROVAL'
  | 'INVESTMENT_ALREADY_APPROVED' | 'INVESTMENT_NOT_PROPOSER' | 'INVESTMENT_INSUFFICIENT_FUNDS'
  | 'INVESTMENT_DUPLICATE_REFERENCE'
  // ledger
  | 'LEDGER_UNBALANCED_ENTRY' | 'LEDGER_INVALID_ENTRY' | 'LEDGER_INVALID_ACCOUNT' | 'LEDGER_ACCOUNT_NOT_FOUND'
  | 'LEDGER_ACCOUNT_INACTIVE' | 'LEDGER_ACCOUNT_CODE_TAKEN' | 'LEDGER_MEMBER_MISMATCH' | 'LEDGER_CURRENCY_MISMATCH'
  | 'LEDGER_ENTRY_NOT_FOUND' | 'LEDGER_ALREADY_REVERSED' | 'LEDGER_CANNOT_REVERSE_REVERSAL'
  // savings products & member accounts
  | 'SAVINGS_PRODUCT_INVALID' | 'SAVINGS_PRODUCT_NOT_FOUND' | 'SAVINGS_PRODUCT_CODE_TAKEN'
  | 'SAVINGS_PRODUCT_DEFAULT_RULE' | 'SAVINGS_PRODUCT_INACTIVE' | 'SAVINGS_PRODUCT_NO_LEDGER_CODE'
  | 'MEMBER_ACCOUNT_NOT_FOUND' | 'MEMBER_ACCOUNT_EXISTS' | 'MEMBER_ACCOUNT_STATE' | 'MEMBER_ACCOUNT_MEMBER_NOT_FOUND'
  // reports
  | 'REPORT_INVALID_PERIOD' | 'REPORT_NOT_A_CASH_ACCOUNT' | 'REPORT_MEMBER_NOT_FOUND'
  // imports
  | 'IMPORT_INVALID_FILE' | 'IMPORT_HAS_PROBLEMS' | 'IMPORT_ALREADY_DONE';
