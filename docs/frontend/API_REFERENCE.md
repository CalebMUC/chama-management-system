# Chama Platform API reference (for frontend builders)

Every endpoint of the backend, grouped by area. Request and response type names refer to
[`api-types.ts`](api-types.ts). Read [`README.md`](README.md) first: it explains the envelope,
authentication, tenancy, permissions and licence rules that apply to all of these.

**Base URL:** `http://localhost:8080` (local). All paths start with `/api/v1`.
**Live contract:** `GET /v3/api-docs` (OpenAPI JSON) and `/swagger-ui.html` on a running backend (not in prod).
A Postman collection with sample bodies is at [`docs/api/postman/Chama-API.postman_collection.json`](../api/postman/Chama-API.postman_collection.json).

## How to read an entry

| Field | Meaning |
|---|---|
| **Auth** | `public`: send **no** `Authorization` header. `bearer`: send `Authorization: Bearer <accessToken>`. |
| **Chama header** | `X-Chama-Code: <code>` is needed only on public auth endpoints. Logged-in requests never need it. |
| **Permission** | A `ChamaPermission` the caller must hold (from `GET /members/me`). `active member` means any ACTIVE membership, no specific permission. `ROLE_ADMIN` means platform administrator. |
| **Feature** | A `LicenseFeature` the chama's licence must include, else `403 LICENSE_FEATURE_NOT_INCLUDED`. |
| **→** | HTTP status and the type of `data` in the success envelope. `Paged<T>` means `data: T[]` plus `meta`. |
| **Errors** | Domain error codes this endpoint can return, on top of the generic ones listed at the end. |

Every request body is JSON (`Content-Type: application/json`) unless stated. Every list that takes
`page`/`size` is zero-based, default `size=50`, max `100`.

---

## 1. Auth & account (`/api/v1/auth`)

Licence state never blocks these endpoints.

### POST `/auth/register`
Self-registration of a member into a chama. Creates the account **and** a membership application (`PENDING_APPROVAL`). Sends a verification email.
- Auth: public · Chama header: **required** (the chama they are joining)
- Body: `RegisterRequest`
```json
{ "email": "jane@example.com", "phoneNumber": "0712345678", "password": "Secret123", "firstName": "Jane", "lastName": "Wanjiku" }
```
- → `201 UserResponse` — "Account created. Check your email for a link to verify your address."
- Errors: `VALIDATION_FAILED`, `AUTH_USER_ALREADY_EXISTS` (409, email already in this chama), `INVALID_PHONE_NUMBER`, `AUTH_WEAK_PASSWORD`, `AUTH_PASSWORD_TOO_LONG`, `TENANT_REQUIRED` (no header), `TENANT_NOT_FOUND` (unknown code)
- After success: log them in (`/auth/login`) and show the "waiting for approval" screen.

### POST `/auth/login`
- Auth: public · Chama header: **required for chama users; omit for platform administrators**
- Body: `LoginRequest` `{ "email": "...", "password": "..." }`
- → `200 AuthResponse` — "Logged in"
- Errors: `AUTH_INVALID_CREDENTIALS` (401, wrong email *or* password, deliberately indistinguishable), `AUTH_ACCOUNT_LOCKED` (429, `error.retryAfterSeconds` set; 5 failures in 15 min), `AUTH_ACCOUNT_DISABLED` (403), `AUTH_EMAIL_NOT_VERIFIED` (403, only if the server requires verification; off by default), `TENANT_NOT_FOUND` (404, wrong chama code)

### POST `/auth/refresh`
Exchanges a refresh token for a new token pair. **The old refresh token dies.** Presenting an already-rotated token is treated as theft and revokes every session of the user. Never run two refreshes at once (README §4.4).
- Auth: public (**do not** send the expired bearer token) · Chama header: **required for chama users** (the code stored at login); omit for platform admins
- Body: `RefreshTokenRequest` `{ "refreshToken": "..." }`
- → `200 AuthResponse` — "Session refreshed"
- Errors: `AUTH_INVALID_TOKEN` (401: unknown, expired, revoked, reused, or wrong/missing chama header) → end the session; `AUTH_ACCOUNT_DISABLED` (403)

### POST `/auth/logout`
Ends this device's session. Idempotent; succeeds even for an unknown token.
- Auth: public · Chama header: required for chama users
- Body: `RefreshTokenRequest`
- → `200 null` — "Logged out"

### POST `/auth/logout-all`
Ends every session of the caller (all devices).
- Auth: bearer · Body: none
- → `200 null` — "Logged out of all devices". Then clear local tokens.

### GET `/auth/me`
- Auth: bearer
- → `200 UserResponse`. `roles` contains `ROLE_ADMIN` for platform administrators (whose `chamaId`/`chamaCode` are null).
- `mustChangePassword: true` → the account still has the temporary password an official set (`POST /members`). Send the
  user to a change-password screen: until `POST /auth/password/change` succeeds, **every other endpoint** except
  `GET /auth/me`, `/auth/logout`, `/auth/logout-all` and `/auth/refresh` answers `403 AUTH_PASSWORD_CHANGE_REQUIRED`.

### POST `/auth/email/verify`
Called by the `/verify-email?chama=<code>&token=<token>` page.
- Auth: public · Chama header: the `chama` query param from the link (absent for platform admins)
- Body: `TokenRequest` `{ "token": "<token from link>" }`
- → `200 null` — "Email verified"
- Errors: `AUTH_LINK_INVALID` (400: unknown, used, superseded or expired after 24 h)

### POST `/auth/email/verify/resend`
- Auth: public · Chama header: required for chama users
- Body: `EmailRequest` `{ "email": "..." }`
- → `202 null` — always the same answer whether or not the email exists. At most one email per 60 s per account (silently).

### POST `/auth/password/forgot`
- Auth: public · Chama header: required for chama users
- Body: `EmailRequest`
- → `202 null` — always the same answer. The email links to `/reset-password?chama=<code>&token=<token>` (valid 30 min).

### POST `/auth/password/reset`
Also used by imported members to set their first password (their link is valid 7 days, and using it verifies the email).
- Auth: public · Chama header: the `chama` query param from the link
- Body: `ResetPasswordRequest` `{ "token": "...", "newPassword": "NewSecret1" }`
- → `200 null` — "Password reset. Log in with your new password." All existing sessions are ended.
- Errors: `AUTH_LINK_INVALID`, `VALIDATION_FAILED` / `AUTH_WEAK_PASSWORD` / `AUTH_PASSWORD_TOO_LONG`

### POST `/auth/password/change`
- Auth: bearer
- Body: `ChangePasswordRequest`
- → `200 AuthResponse` — "Password changed. Your other devices have been logged out." **Replace stored tokens with the returned pair**; the old refresh token is revoked.
- Also clears `mustChangePassword`: the returned tokens are no longer limited. Reload the app context (`/auth/me`, `/members/me`, …) afterwards.
- Errors: `AUTH_CURRENT_PASSWORD_INCORRECT` (400, show on the current-password field), password policy errors

---

## 2. My chama (`/api/v1/chama`)

### GET `/chama`
- Auth: bearer · Permission: `CHAMA_VIEW` · Works even when the licence is expired/suspended
- → `200 ChamaResponse`

### PUT `/chama/settings`
Replaces all settings. Audit-logged with before/after.
- Permission: `CHAMA_SETTINGS_MANAGE`
- Body: `ChamaSettingsRequest` (all 11 fields required; ranges in `api-types.ts`)
- → `200 ChamaResponse` — "Settings updated"
- Errors: `CHAMA_INVALID_SETTINGS` (400; `message` says which rule)
- Effects: contribution amount/frequency apply to periods opened from now on; loan settings are the default loan product's terms.

### GET `/chama/license`
- Permission: `CHAMA_VIEW` · Works when the licence is unusable (so members can see why)
- → `200 LicenseView`

---

## 3. Members (`/api/v1/members`) & roles (`/api/v1/roles`)

No licence feature needed. `{membershipId}` below is `MemberView.membershipId`, **not** the user id.

### GET `/members/me`
The caller's own membership in any status, plus effective permissions. **Call it on every app start**; it drives the whole UI.
- Auth: bearer (any chama user, including applicants) · Works when the licence is unusable
- → `200 MyMembershipView` (`permissions` empty unless `ACTIVE`)
- Errors: `TENANT_REQUIRED` (400) if called by a platform admin

### GET `/members`
- Permission: `MEMBER_VIEW`
- Query: `status?: MembershipStatus`, `page`, `size`. Sorted by member number, then application date.
- → `200 Paged<MemberView>`
- Use `status=ACTIVE` for officials' member pickers (contributions, fines, statements). Fetch all pages (≤100 per page) and cache.
- Plain members do not hold `MEMBER_VIEW` by default; their guarantor picker uses `GET /loans/guarantor-candidates`.

### POST `/members`
An official opens a member's account directly, with their KYC details and a temporary password. The member is `ACTIVE`
at once with the next member number (no approval step), **no email is sent** (the address is taken as confirmed), and
the member must replace the password at first sign-in (`UserResponse.mustChangePassword`). Give the official the chama
code, email and temporary password to hand over.
- Permission: `MEMBER_INVITE` · Body: `AddMemberRequest`
  ```json
  { "firstName": "Wanjiru", "lastName": "Kamau", "email": "wanjiru@example.com", "phoneNumber": "0712 345 678",
    "temporaryPassword": "Karibu2026",
    "kyc": { "idType": "NATIONAL_ID", "idNumber": "12345678", "dateOfBirth": "1990-04-12", "gender": "FEMALE",
             "county": "Nairobi", "town": "Kasarani", "physicalAddress": "Mwiki, House 12" } }
  ```
  `temporaryPassword` follows the registration rules. `kyc.town` and `kyc.physicalAddress` are optional.
- → `201 MemberView` — "Member added. Give them the temporary password; they choose their own when they first sign in."
- Errors: `MEMBER_ALREADY_EXISTS` (409, email), `MEMBER_ID_NUMBER_TAKEN` (409, another member holds the document),
  `MEMBER_UNDER_AGE` (400, under 18), `MEMBER_INVALID_ID_NUMBER` (400), `MEMBER_INVALID_DATE_OF_BIRTH` (400),
  `LICENSE_MEMBER_LIMIT_REACHED` (422), `INVALID_PHONE_NUMBER` (400), `VALIDATION_FAILED` (field paths like `kyc.county`)

### POST `/members/invitations`
Emails a join link (`/join?chama=<code>`). The invitee still registers and is approved.
- Permission: `MEMBER_INVITE` · Body: `InviteMemberRequest` `{ "email": "..." }`
- → `201 InvitationView`
- Errors: `MEMBER_ALREADY_EXISTS` (409)

### GET `/members/{membershipId}/kyc`
- Permission: `MEMBER_VIEW`
- → `200 MemberKycView`, or `200 null` when nothing was recorded (e.g. members who registered themselves)
- Errors: `MEMBER_NOT_FOUND`

### PUT `/members/{membershipId}/kyc`
Records or corrects a member's KYC details (any status). The audit log records `MEMBER_KYC_RECORDED` / `MEMBER_KYC_UPDATED`
without the identity data itself.
- Permission: `MEMBER_INVITE` · Body: `KycRequest` (the `kyc` object above)
- → `200 MemberKycView` — "KYC details saved"
- Errors: as the `kyc` part of `POST /members`, plus `MEMBER_NOT_FOUND`

### POST `/members/{membershipId}/approve`
Activates a `PENDING_APPROVAL` applicant, assigns the next member number and emails them a welcome. Their accounts in mandatory savings products open by themselves.
- Permission: `MEMBER_APPROVE` · Body: none
- → `200 MemberView` — "Member approved"
- Errors: `MEMBER_NOT_FOUND`, `MEMBER_INVALID_STATE` (422), `LICENSE_MEMBER_LIMIT_REACHED` (422), `MEMBER_SELF_MANAGEMENT` (403)

### POST `/members/{membershipId}/reject`
- Permission: `MEMBER_APPROVE` · Body: none · Only `PENDING_APPROVAL`
- → `200 MemberView` — "Membership request rejected"

### POST `/members/{membershipId}/suspend`
- Permission: `MEMBER_SUSPEND` · Body: `SuspendMemberRequest` `{ "reason": "..." }` · Only `ACTIVE`
- → `200 MemberView` — "Member suspended". The member loses every permission on their next request.
- Errors: `MEMBER_SELF_MANAGEMENT`, `MEMBER_LAST_CHAIRPERSON` (422), `MEMBER_INVALID_STATE`

### POST `/members/{membershipId}/reactivate`
- Permission: `MEMBER_SUSPEND` · Body: none · Only `SUSPENDED`
- → `200 MemberView` — "Member reactivated"

### PUT `/members/{membershipId}/roles`
Replaces the member's roles (`MEMBER` is always kept). Only for `ACTIVE` members.
- Permission: `MEMBER_ROLES_MANAGE` · Body: `ChangeRolesRequest` `{ "roles": ["MEMBER","TREASURER"] }`
- → `200 MemberView` — "Roles updated"
- Errors: `MEMBER_SELF_MANAGEMENT` (cannot change your own roles), `MEMBER_LAST_CHAIRPERSON`

### GET `/roles`
- Permission: `MEMBER_VIEW`
- → `200 RolePermissionsMap` e.g. `{ "MEMBER": ["CHAMA_VIEW", ...], "TREASURER": [...], ... }`

### PUT `/roles/{role}/permissions`
Replaces one role's permission set, effective immediately for everyone holding it.
- Permission: `ROLE_PERMISSIONS_MANAGE` · Path: `role: ChamaRole` · Body: `RolePermissionsRequest`
- → `200 ChamaPermission[]` — "Permissions updated"
- Errors: `ROLE_PERMISSIONS_LOCKOUT` (422: CHAIRPERSON must keep `MEMBER_ROLES_MANAGE` and `ROLE_PERMISSIONS_MANAGE`)

---

## 4. Contributions (`/api/v1/contributions`) · Feature `CONTRIBUTIONS`

Periods are opened automatically (and missed ones caught up) whenever contributions are used.

### POST `/contributions`
Records money received from a member; posts to the ledger; emails the member a receipt.
- Permission: `CONTRIBUTION_RECORD` · Body: `RecordPaymentRequest`
```json
{ "memberUserId": "…", "amountMinor": 500000, "method": "MPESA", "reference": "QK7H2XY9ZB", "paidOn": "2026-11-05" }
```
- With an explicit split: `"allocations": [{ "productId": "…", "amountMinor": 450000 }, { "productId": "…", "amountMinor": 50000 }]` (must add up to `amountMinor`).
- → `201 PaymentView` — "Contribution recorded"
- Errors: `CONTRIBUTION_INVALID_PAYMENT` (400: missing reference for MPESA/BANK, future date, allocations don't add up, …), `CONTRIBUTION_MEMBER_NOT_FOUND` (not ACTIVE/SUSPENDED), `CONTRIBUTION_SELF_RECORDING` (403: own contribution), `CONTRIBUTION_DUPLICATE_REFERENCE` (409), `SAVINGS_PRODUCT_INACTIVE`

### GET `/contributions`
Payments, newest first.
- Permission: `CONTRIBUTION_VIEW_ALL` · Query: `memberUserId?`, `from?`, `to?` (ISO dates on `paidOn`), `status?: RECORDED|REVERSED`, `page`, `size`
- → `200 Paged<PaymentView>`

### GET `/contributions/{paymentId}`
- Permission: `CONTRIBUTION_VIEW_ALL` → `200 PaymentView` · Errors: `CONTRIBUTION_NOT_FOUND`

### POST `/contributions/{paymentId}/reverse`
Marks the payment reversed and reverses its ledger entry.
- Permission: `CONTRIBUTION_REVERSE` · Body: `ReversePaymentRequest` `{ "reason": "..." }`
- → `200 PaymentView` — "Contribution reversed"
- Errors: `CONTRIBUTION_ALREADY_REVERSED` (409), `CONTRIBUTION_SELF_RECORDING` (own payment)

### GET `/contributions/periods`
- Permission: `CONTRIBUTION_VIEW_ALL` → `200 PeriodSummaryView[]` (newest first, not paged)

### GET `/contributions/periods/{periodId}`
- Permission: `CONTRIBUTION_VIEW_ALL` → `200 PeriodDetailView` · Errors: `CONTRIBUTION_PERIOD_NOT_FOUND`

### GET `/contributions/standings`
Every seated member's totals, most in arrears first.
- Permission: `CONTRIBUTION_VIEW_ALL` · Query: `inArrears?: boolean` (default false)
- → `200 StandingView[]`

### GET `/contributions/me`
- Permission: active member → `200 MyContributionsView`

---

## 5. Savings products (`/api/v1/savings-products`) · Feature `CONTRIBUTIONS`

Every chama has a default product "Deposits" (`defaultProduct: true`), which follows the chama's contribution setting and is always active and mandatory.

### GET `/savings-products`
- Permission: active member → `200 SavingsProductView[]` (active first, by allocation priority)

### GET `/savings-products/{productId}`
- Permission: active member → `200 SavingsProductView` · Errors: `SAVINGS_PRODUCT_NOT_FOUND`

### POST `/savings-products`
Creates the product and its own ledger account. A mandatory product opens an account for every member at once and is owed from the next period.
- Permission: `PRODUCTS_MANAGE` · Body: `CreateSavingsProductRequest`
```json
{ "code": "SHR", "name": "Share capital", "kind": "SHARE_CAPITAL", "contributionMinor": 50000, "mandatory": true }
```
- → `201 SavingsProductView` — "Savings product created"
- Errors: `SAVINGS_PRODUCT_INVALID`, `SAVINGS_PRODUCT_CODE_TAKEN`, `SAVINGS_PRODUCT_NO_LEDGER_CODE`, `LEDGER_ACCOUNT_CODE_TAKEN`

### PUT `/savings-products/{productId}`
Applies to periods opened from now on.
- Permission: `PRODUCTS_MANAGE` · Body: `UpdateSavingsProductRequest`
- → `200 SavingsProductView` — "Savings product updated"
- Errors: `SAVINGS_PRODUCT_DEFAULT_RULE` (422: e.g. changing the default product's contribution; change chama settings instead)

### POST `/savings-products/{productId}/deactivate` · POST `/savings-products/{productId}/activate`
- Permission: `PRODUCTS_MANAGE` · Body: none → `200 SavingsProductView`
- Errors: `SAVINGS_PRODUCT_DEFAULT_RULE` (cannot deactivate the default)

---

## 6. Member accounts (`/api/v1/member-accounts`) · Feature `CONTRIBUTIONS`

One account per member per savings product, balances from the ledger.

### GET `/member-accounts`
- Permission: `MEMBER_ACCOUNTS_VIEW_ALL` · Query: `memberUserId?`, `productId?`, `status?: ACTIVE|CLOSED` (not paged)
- → `200 MemberAccountView[]` (by account number)

### GET `/member-accounts/{accountId}`
- Permission: `MEMBER_ACCOUNTS_VIEW_ALL` → `200 MemberAccountView` · Errors: `MEMBER_ACCOUNT_NOT_FOUND`

### GET `/member-accounts/{accountId}/statement`
- Permission: `MEMBER_ACCOUNTS_VIEW_ALL` · Query: `from?`, `to?` → `200 MemberAccountStatementView` (oldest first)

### GET `/member-accounts/me`
- Permission: active member → `200 MyAccountsView`

### GET `/member-accounts/me/{accountId}/statement`
- Permission: active member (own account only, else 404) · Query: `from?`, `to?` → `200 MemberAccountStatementView`

### POST `/member-accounts`
Opens an account in a non-mandatory product.
- Permission: `MEMBER_ACCOUNTS_MANAGE` · Body: `OpenAccountRequest` `{ "memberUserId": "…", "productId": "…" }`
- → `201 MemberAccountView` — "Account opened"
- Errors: `MEMBER_ACCOUNT_EXISTS` (409), `MEMBER_ACCOUNT_MEMBER_NOT_FOUND`, `SAVINGS_PRODUCT_INACTIVE`

### POST `/member-accounts/{accountId}/close`
Zero balance only; not a mandatory product's account while the member is seated.
- Permission: `MEMBER_ACCOUNTS_MANAGE` · Body: `CloseAccountRequest` `{ "reason": "..." }`
- → `200 MemberAccountView` — "Account closed" · Errors: `MEMBER_ACCOUNT_STATE` (422)

### POST `/member-accounts/{accountId}/reopen`
- Permission: `MEMBER_ACCOUNTS_MANAGE` · Body: none → `200 MemberAccountView` — dues start again from today

---

## 7. Penalties (`/api/v1/penalties`) · Feature `PENALTIES`

Late-contribution penalties are created automatically (nightly, and whenever the list is fetched).

### GET `/penalties`
- Permission: `PENALTY_VIEW_ALL` · Query: `memberUserId?`, `status?: OUTSTANDING|PAID|WAIVED`, `page`, `size`
- → `200 Paged<PenaltyView>` (newest first)

### GET `/penalties/{penaltyId}`
- Permission: `PENALTY_VIEW_ALL` → `200 PenaltyDetailView` · Errors: `PENALTY_NOT_FOUND`

### POST `/penalties/fines`
A fine for something other than lateness (e.g. a missed meeting). Posted to the ledger.
- Permission: `PENALTY_RECORD` · Body: `FineRequest` `{ "memberUserId": "…", "amountMinor": 20000, "reason": "Missed meeting 2026-11-02" }`
- → `201 PenaltyView` — "Fine recorded"
- Errors: `PENALTY_SELF_HANDLING` (403), `PENALTY_MEMBER_NOT_FOUND`, `PENALTY_INVALID`

### POST `/penalties/{penaltyId}/payments`
Part-payments allowed, up to what is outstanding.
- Permission: `PENALTY_RECORD` · Body: `PenaltyPaymentRequest` `{ "amountMinor": 10000, "method": "CASH" }`
- → `201 PenaltyView` — "Penalty payment recorded"
- Errors: `PENALTY_CLOSED` (409), `PENALTY_SELF_HANDLING`, `PENALTY_INVALID` (over outstanding, missing reference, future date), `PENALTY_DUPLICATE_REFERENCE`

### POST `/penalties/{penaltyId}/waive`
Waives what is still outstanding.
- Permission: `PENALTY_WAIVE` · Body: `WaiveRequest` `{ "reason": "..." }`
- → `200 PenaltyView` — "Penalty waived" · Errors: `PENALTY_CLOSED`, `PENALTY_SELF_HANDLING`

### POST `/penalties/assess`
Runs the late-contribution assessment now (normally nightly). Each late period is penalised once per member.
- Permission: `PENALTY_RECORD` · Body: none
- → `200 PenaltyView[]` (the new ones) — "No new penalties" / "3 new penalties"

### GET `/penalties/me`
- Permission: active member → `200 MyPenaltiesView`

---

## 8. Loan products (`/api/v1/loan-products`) · Feature `LOANS`

### GET `/loan-products`
- Permission: active member → `200 LoanProductView[]` (active first). Use for the apply-for-loan product picker.

### GET `/loan-products/{productId}`
- Permission: active member → `200 LoanProductView` · Errors: `LOAN_PRODUCT_NOT_FOUND`

### POST `/loan-products`
- Permission: `PRODUCTS_MANAGE` · Body: `LoanProductRequest` (with `code`)
```json
{ "code": "EMERG", "name": "Emergency loan", "interestBasisPoints": 500, "maxTermMonths": 3, "maxMultiplier": 1,
  "minMembershipMonths": 0, "guarantorThresholdMinor": 0, "guarantorsRequired": 0 }
```
- → `201 LoanProductView` · Errors: `LOAN_PRODUCT_INVALID`, `LOAN_PRODUCT_CODE_TAKEN`

### PUT `/loan-products/{productId}`
Applies to applications made from now on. For the default product only `name` can change (its terms are the chama's loan settings).
- Permission: `PRODUCTS_MANAGE` · Body: `LoanProductRequest` (`code` ignored)
- → `200 LoanProductView` · Errors: `LOAN_PRODUCT_DEFAULT_RULE` (422)

### POST `/loan-products/{productId}/deactivate` · POST `/loan-products/{productId}/activate`
- Permission: `PRODUCTS_MANAGE` → `200 LoanProductView`. Existing loans carry on. The default product cannot be deactivated.

---

## 9. Loans (`/api/v1/loans`) · Feature `LOANS`

Lifecycle: `PENDING_GUARANTORS → PENDING_APPROVAL → APPROVED → ACTIVE → REPAID`, or `REJECTED` / `CANCELLED` before disbursement. Loans not needing guarantors start at `PENDING_APPROVAL`. Flat interest; equal monthly instalments, first due one month after disbursement.

### Member endpoints

#### GET `/loans/eligibility`
What the caller may borrow now under one product.
- Permission: `LOAN_APPLY` · Query: `productId?` (default product if omitted)
- → `200 EligibilityView`. If `eligible` is false, show `reasons` and disable the form.

#### POST `/loans`
- Permission: `LOAN_APPLY` · Body: `ApplyLoanRequest`
```json
{ "productId": "…", "principalMinor": 10000000, "termMonths": 12, "purpose": "School fees",
  "guarantorUserIds": ["…", "…"] }
```
- → `201 LoanDetailView` — "Loan application submitted". Guarantors are emailed.
- Errors: `LOAN_NOT_ELIGIBLE` (422; message joins every reason), `LOAN_INVALID` (term beyond product max, too few guarantors, yourself as guarantor, …), `LOAN_MEMBER_NOT_FOUND` (a guarantor isn't active), `LOAN_PRODUCT_INACTIVE`, `LOAN_PRODUCT_NOT_FOUND`

#### GET `/loans/me`
- Permission: active member → `200 MyLoansView` (each loan with schedule and repayments)

#### GET `/loans/guarantor-candidates`
- Permission: `LOAN_APPLY` → `200 GuarantorCandidate[]` `{ userId, memberNumber, name }`
- Every active member except the caller, by member number. Use this for the guarantor picker: plain members
  do not hold `MEMBER_VIEW`, so they cannot call `GET /members`.

#### GET `/loans/guarantees`
Loans the caller was asked to guarantee.
- Permission: active member → `200 GuaranteeView[]`

#### POST `/loans/{loanId}/guarantee`
The guarantor's answer.
- Permission: active member (must be a requested guarantor) · Body: `GuaranteeResponseRequest` `{ "accept": true }`
- → `200 LoanDetailView` — "You are now a guarantor of this loan" / "You declined to guarantee this loan"
- Errors: `LOAN_NOT_GUARANTOR` (403), `LOAN_GUARANTOR_RESPONDED` (409), `LOAN_WRONG_STATUS`

#### POST `/loans/{loanId}/guarantors`
Borrower names another guarantor (e.g. after a decline), while the loan is `PENDING_GUARANTORS`.
- Permission: active member (borrower only) · Body: `AddGuarantorRequest` `{ "guarantorUserId": "…" }`
- → `200 LoanDetailView` — "Guarantor asked" · Errors: `LOAN_NOT_BORROWER`, `LOAN_WRONG_STATUS`, `LOAN_INVALID`, `LOAN_MEMBER_NOT_FOUND`

#### POST `/loans/{loanId}/cancel`
Borrower withdraws before disbursement.
- Permission: active member (borrower only) · Body: none → `200 LoanDetailView` — "Loan application cancelled"
- Errors: `LOAN_NOT_BORROWER`, `LOAN_WRONG_STATUS`

### Official endpoints

#### GET `/loans`
- Permission: `LOAN_VIEW_ALL` · Query: `memberUserId?`, `status?: LoanStatus`, `page`, `size` → `200 Paged<LoanView>` (newest first)

#### GET `/loans/schedules`
Every loan being repaid (status `ACTIVE`), for following up repayments.
- Permission: `LOAN_VIEW_ALL` → `200` **binary** `.xlsx` (`filename="loan-schedules.xlsx"`), not wrapped in the envelope.
- Sheet *Summary*: one row per loan by member number: principal, interest, total, repaid, outstanding, overdue (amount, instalments, days), next instalment (date, amount) and the last instalment's date. Sheet *Schedules*: one row per instalment with its due date, amount, paid, still to pay and status (Paid, Part paid, Due, Overdue).

#### GET `/loans/{loanId}`
- Permission: `LOAN_VIEW_ALL` → `200 LoanDetailView` · Errors: `LOAN_NOT_FOUND`

#### POST `/loans/{loanId}/approve`
One official's approval; status becomes `APPROVED` when `approvals == requiredApprovals` (the chama's quorum).
- Permission: `LOAN_APPROVE` · Body (optional): `{ "note": "..." }`
- → `200 LoanDetailView` — "Loan approved" or "Approval recorded (1 of 2)"
- Errors: `LOAN_SELF_HANDLING` (own loan), `LOAN_GUARANTOR_CANNOT_APPROVE`, `LOAN_ALREADY_APPROVED` (409), `LOAN_WRONG_STATUS` (not `PENDING_APPROVAL`)

#### POST `/loans/{loanId}/reject`
Before disbursement; the borrower is told the reason.
- Permission: `LOAN_APPROVE` · Body: `LoanRejectRequest` `{ "reason": "..." }` → `200 LoanDetailView` — "Loan rejected"

#### POST `/loans/{loanId}/disburse`
Pays out an `APPROVED` loan from cash/bank/M-Pesa (which must hold enough) and starts the schedule.
- Permission: `LOAN_DISBURSE` · Body: `DisburseRequest` `{ "method": "MPESA", "reference": "QK8...", "disbursedOn": "2026-11-08" }`
- → `200 LoanDetailView` — "Loan disbursed"
- Errors: `LOAN_INSUFFICIENT_FUNDS` (422), `LOAN_WRONG_STATUS`, `LOAN_SELF_HANDLING`, `LOAN_DUPLICATE_REFERENCE`, `LOAN_INVALID`

#### POST `/loans/{loanId}/repayments`
Up to the outstanding amount; clears the oldest instalment first.
- Permission: `LOAN_REPAYMENT_RECORD` · Body: `RepaymentRequest`
- → `201 LoanDetailView` — "Repayment recorded" or "Loan fully repaid" (status `REPAID`)
- Errors: `LOAN_WRONG_STATUS` (not `ACTIVE`), `LOAN_SELF_HANDLING`, `LOAN_INVALID` (over outstanding), `LOAN_DUPLICATE_REFERENCE`

---

## 10. Payment submissions (`/api/v1/payment-submissions`)

Members report payments they made themselves (usually by M-Pesa): a **contribution**, a **penalty
payment** or a **loan repayment**. A report moves no money and changes no balance. It waits as
`PENDING` until an official who holds the matching record permission (the treasurer by default) approves it.
Approving records the real payment through `POST /contributions`, `/penalties/{id}/payments` or
`/loans/{id}/repayments` under the approver's name, in the same transaction, with all the same rules.
Rejecting needs a reason. The member is emailed either way, except for an approved contribution,
which already sends the usual contribution receipt.

| `kind` | `targetId` | Licence feature | Reviewer needs |
|---|---|---|---|
| `CONTRIBUTION` | none | `CONTRIBUTIONS` | `CONTRIBUTION_RECORD` |
| `PENALTY_PAYMENT` | the penalty (from `GET /penalties/me` or `MemberDashboardView.penalties.outstanding`) | `PENALTIES` | `PENALTY_RECORD` |
| `LOAN_REPAYMENT` | the loan (from `GET /loans/me`; must be `ACTIVE`) | `LOANS` | `LOAN_REPAYMENT_RECORD` |

Statuses: `PENDING` → `APPROVED` | `REJECTED` (with `reviewNote` = reason) | `CANCELLED` (withdrawn by the member).
Nobody reviews their own report, so a treasurer's own payment goes to another official (e.g. the chairperson).

### POST `/payment-submissions`
- Permission: `PAYMENT_SUBMIT` (every role by default) · Body: `SubmitPaymentRequest`
  `{ "kind": "PENALTY_PAYMENT", "targetId": "…", "amountMinor": 50000, "method": "MPESA", "reference": "QK7…", "paidOn": "2026-11-09", "notes": "…" }`
  `reference` is required unless `method` is `CASH`; `paidOn` defaults to today and cannot be in the future.
- → `201 SubmissionView` — "Payment reported. The treasurer will confirm it once they have checked it."
- Errors: `SUBMISSION_INVALID` (400), `SUBMISSION_TARGET_INVALID` (422: not your penalty/loan, already
  closed, loan not active, more than is outstanding), `SUBMISSION_DUPLICATE_REFERENCE` (409: the code was already
  reported or recorded), `LICENSE_FEATURE_NOT_INCLUDED`

### GET `/payment-submissions/me?status=&page=&size=`
- Permission: active member → `200 Paged<SubmissionView>` (pending first, then newest first)

### POST `/payment-submissions/{id}/cancel`
- Permission: active member, the submitter only, while `PENDING` → `200 SubmissionView` — "Report withdrawn"
- Errors: `SUBMISSION_NOT_SUBMITTER` (403), `SUBMISSION_WRONG_STATUS` (409)

### GET `/payment-submissions?status=&kind=&memberUserId=&page=&size=`
The review queue: only the kinds the caller may review. Pending ones first (oldest first), then the rest newest first.
- Permission: any of `CONTRIBUTION_RECORD`, `PENALTY_RECORD`, `LOAN_REPAYMENT_RECORD` → `200 Paged<SubmissionView>`

### GET `/payment-submissions/reviewable-kinds`
- Permission: as the queue → `200 SubmissionKind[]` (for the queue's kind filter)

### GET `/payment-submissions/pending-count`
For the "Payments to confirm" nav badge. Pending reports of the kinds the caller may review, **excluding their
own** (another official reviews those). Works without `REPORT_VIEW_ALL`, unlike `GET /dashboard` `pending`.
- Permission: as the queue → `200 SubmissionReviewCounts` `{ contributions, penaltyPayments, loanRepayments, total }`
  (a kind the caller may not review is `0`)

### GET `/payment-submissions/{id}`
- Permission: the submitter, or an official who may review its kind → `200 SubmissionView`
- Errors: `SUBMISSION_NOT_FOUND`, `ACCESS_DENIED`

### POST `/payment-submissions/{id}/approve`
- Permission: the record permission for its `kind` · Body (optional): `{ "note": "Seen on the statement" }`
- → `200 SubmissionView` — "Payment confirmed and recorded"
- Errors: `SUBMISSION_WRONG_STATUS` (409), `SUBMISSION_SELF_REVIEW` (403), `ACCESS_DENIED`, plus the owning
  module's errors (e.g. `PENALTY_INVALID` / `LOAN_INVALID` if the penalty or loan was paid down meanwhile)

### POST `/payment-submissions/{id}/reject`
- Permission: as approve · Body: `{ "reason": "Not on the M-Pesa statement" }` (required)
- → `200 SubmissionView` — "Payment rejected" · Errors: as approve, `VALIDATION_FAILED` (no reason)

---

## 11. Expenses (`/api/v1/expenses`) · Feature `EXPENSES`

Maker-checker: `PENDING_APPROVAL → APPROVED → PAID`, or `REJECTED` / `CANCELLED` before payment. Every member may view (`EXPENSE_VIEW` is a default member permission).

### POST `/expenses`
- Permission: `EXPENSE_CREATE` · Body: `ExpenseRequest`
```json
{ "amountMinor": 350000, "payee": "Kenya Power", "description": "October electricity, Airbnb unit",
  "accountCode": "5000", "investmentId": "…", "incurredOn": "2026-11-03" }
```
- → `201 ExpenseView` — "Expense recorded for approval"
- Errors: `EXPENSE_INVALID`, `EXPENSE_ACCOUNT_INVALID` (not an active EXPENSE account), `EXPENSE_INVESTMENT_INVALID` (not an ACTIVE investment)

### GET `/expenses`
- Permission: `EXPENSE_VIEW` · Query: `status?`, `investmentId?`, `from?`, `to?` (on `incurredOn`), `page`, `size` → `200 Paged<ExpenseView>`

### GET `/expenses/summary`
Paid between two dates (by payment date) per account, plus what awaits payment. Default: this month.
- Permission: `EXPENSE_VIEW` · Query: `from?`, `to?` → `200 ExpenseSummaryView`

### GET `/expenses/{expenseId}`
- Permission: `EXPENSE_VIEW` → `200 ExpenseView` · Errors: `EXPENSE_NOT_FOUND`

### POST `/expenses/{expenseId}/approve`
- Permission: `EXPENSE_APPROVE` · Body (optional): `{ "note": "..." }`
- → `200 ExpenseView` — "Expense approved" / "Approval recorded (1 of 2)"
- Errors: `EXPENSE_SELF_APPROVAL` (you recorded it), `EXPENSE_ALREADY_APPROVED`, `EXPENSE_WRONG_STATUS`

### POST `/expenses/{expenseId}/reject`
- Permission: `EXPENSE_APPROVE` · Body: `{ "reason": "..." }` → `200 ExpenseView` · Errors: `EXPENSE_SELF_APPROVAL`, `EXPENSE_WRONG_STATUS`

### POST `/expenses/{expenseId}/cancel`
Only whoever recorded it, before payment.
- Permission: `EXPENSE_CREATE` · Body: none → `200 ExpenseView` · Errors: `EXPENSE_NOT_REQUESTER`, `EXPENSE_WRONG_STATUS`

### POST `/expenses/{expenseId}/pay`
Pays an `APPROVED` expense through the ledger; the source account must hold enough.
- Permission: `EXPENSE_CREATE` · Body: `ExpensePayRequest` `{ "method": "BANK", "reference": "FT2611..." }`
- → `200 ExpenseView` — "Expense paid"
- Errors: `EXPENSE_INSUFFICIENT_FUNDS` (422), `EXPENSE_WRONG_STATUS`, `EXPENSE_DUPLICATE_REFERENCE`, `EXPENSE_INVALID`

---

## 12. Investments (`/api/v1/investments`) · Feature `INVESTMENTS`

`PROPOSED → APPROVED → ACTIVE → CLOSED`, or `REJECTED` / `CANCELLED` before funding. Every member may view.

### POST `/investments`
- Permission: `INVESTMENT_MANAGE` · Body: `ProposeInvestmentRequest`
```json
{ "name": "CIC Money Market Fund", "type": "MONEY_MARKET_FUND", "institution": "CIC Asset Management",
  "amountMinor": 20000000, "expectedReturnBasisPoints": 1250 }
```
- → `201 InvestmentView` — "Investment proposed for approval" · Errors: `INVESTMENT_INVALID`

### GET `/investments`
- Permission: `INVESTMENT_VIEW` · Query: `status?`, `type?`, `page`, `size` → `200 Paged<InvestmentView>`

### GET `/investments/portfolio`
- Permission: `INVESTMENT_VIEW` → `200 PortfolioView`

### GET `/investments/{investmentId}`
- Permission: `INVESTMENT_VIEW` → `200 InvestmentDetailView` · Errors: `INVESTMENT_NOT_FOUND`

### POST `/investments/{investmentId}/approve`
- Permission: `INVESTMENT_APPROVE` · Body (optional): `{ "note": "..." }` → `200 InvestmentView` — "Investment approved" / "Approval recorded (n of m)"
- Errors: `INVESTMENT_SELF_APPROVAL`, `INVESTMENT_ALREADY_APPROVED`, `INVESTMENT_WRONG_STATUS`

### POST `/investments/{investmentId}/reject`
- Permission: `INVESTMENT_APPROVE` · Body: `{ "reason": "..." }` → `200 InvestmentView`

### POST `/investments/{investmentId}/cancel`
Only the proposer, before funding.
- Permission: `INVESTMENT_MANAGE` · Body: none → `200 InvestmentView` · Errors: `INVESTMENT_NOT_PROPOSER`

### POST `/investments/{investmentId}/fund`
Puts in exactly the approved `amountMinor`.
- Permission: `INVESTMENT_MANAGE` · Body: `FundRequest` → `200 InvestmentDetailView` — "Investment funded"
- Errors: `INVESTMENT_INSUFFICIENT_FUNDS` (422), `INVESTMENT_WRONG_STATUS`, `INVESTMENT_DUPLICATE_REFERENCE`

### POST `/investments/{investmentId}/deposits`
Top-up of an `ACTIVE` investment. No new approval.
- Permission: `INVESTMENT_MANAGE` · Body: `DepositRequest` → `201 InvestmentDetailView` · Errors: `INVESTMENT_INSUFFICIENT_FUNDS`

### POST `/investments/{investmentId}/income`
Interest/dividends/rent paid into cash/bank/M-Pesa, or `REINVESTED` (adds to book value).
- Permission: `INVESTMENT_MANAGE` · Body: `IncomeRequest` → `201 InvestmentDetailView`

### POST `/investments/{investmentId}/withdrawals`
Partial; must be less than the book value (use close to take everything).
- Permission: `INVESTMENT_MANAGE` · Body: `WithdrawalRequest` → `201 InvestmentDetailView` · Errors: `INVESTMENT_INVALID`

### POST `/investments/{investmentId}/valuations`
Records current worth; not posted to the books.
- Permission: `INVESTMENT_MANAGE` · Body: `ValuationRequest` `{ "valueMinor": 21500000, "valuedOn": "2026-11-01" }` → `201 InvestmentDetailView`

### POST `/investments/{investmentId}/close`
Sold/redeemed; proceeds minus book value is posted as gain or loss.
- Permission: `INVESTMENT_MANAGE` · Body: `CloseInvestmentRequest` → `200 InvestmentDetailView` — "Investment closed"

---

## 13. Ledger (`/api/v1/ledger`)

No licence feature needed. Double-entry books; entries are never edited or deleted, only reversed.

Default chart: `1000` Cash on hand, `1010` Bank account, `1020` Mobile money (M-Pesa), `1100` Loans receivable*, `1110` Penalties receivable*, `1200` Investments, `2000` Member savings*, `3000` Retained surplus, `4000` Loan interest income, `4100` Penalty income, `4200` Investment income, `4900` Other income, `5000` Operating expenses, `5100` Bank and transaction charges. (* member ledger: lines need `memberUserId`.) Savings products add accounts `2010…2090` / `3100…3190` (member ledgers).

### GET `/ledger/accounts`
- Permission: `LEDGER_VIEW` → `200 LedgerAccountView[]`. Also the source of expense-account options (`type === 'EXPENSE' && active`).

### POST `/ledger/accounts`
- Permission: `LEDGER_ACCOUNTS_MANAGE` · Body: `CreateLedgerAccountRequest` `{ "code": "1011", "name": "Equity Bank account", "type": "ASSET" }`
- → `201 LedgerAccountView` · Errors: `LEDGER_ACCOUNT_CODE_TAKEN`, `LEDGER_INVALID_ACCOUNT`

### PUT `/ledger/accounts/{accountId}/status`
- Permission: `LEDGER_ACCOUNTS_MANAGE` · Body: `{ "active": false }` → `200 LedgerAccountView`
- Errors: `LEDGER_INVALID_ACCOUNT` (system accounts, `systemKey != null`, cannot be deactivated)

### GET `/ledger/accounts/{accountId}/statement`
- Permission: `LEDGER_VIEW` · Query: `from?`, `to?`, `memberUserId?` (narrows a member-ledger account to one member) → `200 LedgerStatementView`

### GET `/ledger/entries`
- Permission: `LEDGER_VIEW` · Query: `from?`, `to?`, `sourceType?`, `page`, `size` → `200 Paged<EntryView>` (newest first)

### GET `/ledger/entries/{entryId}`
- Permission: `LEDGER_VIEW` → `200 EntryView` · Errors: `LEDGER_ENTRY_NOT_FOUND`

### POST `/ledger/entries`
Manual journal entry (opening balances, bank charges, corrections). Debits must equal credits.
- Permission: `LEDGER_POST` · Body: `ManualEntryRequest`
```json
{ "entryDate": "2026-11-01", "description": "Bank charges October", "reference": "STMT-10",
  "lines": [ { "accountCode": "5100", "debitMinor": 25000 }, { "accountCode": "1010", "creditMinor": 25000 } ] }
```
- → `201 EntryView` — "Entry #57 posted"
- Errors: `LEDGER_UNBALANCED_ENTRY`, `LEDGER_INVALID_ENTRY` (both/neither debit and credit on a line, future date), `LEDGER_ACCOUNT_NOT_FOUND`, `LEDGER_ACCOUNT_INACTIVE`, `LEDGER_MEMBER_MISMATCH` (member missing on a member-ledger line or present on another), `LEDGER_CURRENCY_MISMATCH`

### POST `/ledger/entries/{entryId}/reverse`
Posts the mirror entry dated today.
- Permission: `LEDGER_POST` · Body: `{ "reason": "..." }` → `201 EntryView` — "Reversed as entry #58"
- Errors: `LEDGER_ALREADY_REVERSED` (409), `LEDGER_CANNOT_REVERSE_REVERSAL` (422)
- UX note: reverse business records (contributions, etc.) through their own endpoints, not here; use this for manual entries.

### GET `/ledger/trial-balance`
- Permission: `LEDGER_VIEW` · Query: `asOf?` (default today) → `200 TrialBalanceView`

### GET `/ledger/me`
- Permission: active member → `200 MemberBalancesView`

---

## 14. Dashboards (`/api/v1/dashboard`)

No licence feature needed; sections for unlicensed features are `null`. Screen layouts, states and
TypeScript types: [DASHBOARDS.md](DASHBOARDS.md).

### GET `/dashboard`
- Permission: `REPORT_VIEW_ALL` → `200 ChamaDashboardView`
- `keyFigures` (total contributions, contributions this year, savings, cash, loans outstanding, investments
  market value, penalties outstanding, total assets, net worth), `members`, `cash`, `savings`, `contributions`
  (current period, arrears, `complianceRateBasisPoints`, collected this month / year / ever), `loans` (ageing,
  PAR30, disbursed this year, repaid, due in the next 7 days), `penalties` (owed, assessed / collected / waived
  this year, `topOwing`), `investments` (active, market value, `totalReturnMinor`, `returnBasisPoints`, maturing
  in 30 days), `topMembers` (5), `topInvestments` (5), `thisMonth`, `yearToDate`, `trends` (6 months, oldest
  first), `pending` (including `paymentsAwaitingReview`), `recentEntries`.

### GET `/dashboard/overview`
The chama overview for **every member**: the same chama-wide totals as `GET /dashboard`, with nothing that
names or singles out a member.
- Permission: `CHAMA_VIEW` (every role by default) → `200 ChamaOverviewView`
- `keyFigures`, `members`, `cash`, `savings`, `contributions`, `loans`, `penalties` (`topOwing` always `[]`),
  `investments` + `topInvestments` (null without `INVESTMENT_VIEW`, as is `keyFigures.investmentsMarketValueMinor`),
  `thisMonth`, `yearToDate`, `trends` (6 months). No `topMembers`, `pending` or `recentEntries`.

### GET `/dashboard/top-members?from=&to=&limit=10`
- Permission: `REPORT_VIEW_ALL` · Feature `CONTRIBUTIONS` → `200 TopMembersView`
- Members ranked by contributions paid in the window (default 1 January to today, inclusive), then by
  `complianceRateBasisPoints`. Each: `rank`, `name`, `memberNumber`, `contributedMinor`, `payments`,
  `arrearsMinor`, `latePeriods`, `savingsMinor`. Members who paid nothing in the window are left out.
  `limit` 1–50. `from` after `to` → `400 REPORT_INVALID_PERIOD`.

### GET `/dashboard/top-investments?limit=5`
- Permission: `INVESTMENT_VIEW` · Feature `INVESTMENTS` → `200 TopInvestmentsView`
- Funded investments (active and closed) ranked by `returnBasisPoints`: income less expenses plus the gain in
  value, per 10,000 invested. Each has `investmentId` (link to `/investments/{id}`), `totalReturnMinor`,
  `annualisedReturnBasisPoints` (null under 30 days held), `daysHeld`, `profitable`.

### GET `/dashboard/trends?months=12`
- Permission: `REPORT_VIEW_ALL` → `200 TrendsView`
- `months` 1–24, oldest first: `contributionsMinor` / `contributionPayments` (null without CONTRIBUTIONS),
  `loansDisbursedMinor` (null without LOANS), `incomeMinor`, `expensesMinor`, `surplusMinor`.

### GET `/dashboard/me`
- Permission: active member → `200 MemberDashboardView`
- `netPositionMinor` (savings less loans and penalties owed), `savings`, `contributions` (with
  `contributedYearToDateMinor`, `complianceRateBasisPoints`, `rank` of `rankOutOf`), `loans` (each loan in
  `loans.loans` with progress and next instalment), `penalties` (each outstanding penalty), `submissions.pending`,
  `topInvestments` (3, only with `INVESTMENT_VIEW`).

---

## 15. Reports (`/api/v1/reports`) · Feature `REPORTS`

All read-only, JSON only (no PDF/CSV endpoint; export client-side if needed).

| Endpoint | Permission | Query (defaults) | → `data` |
|---|---|---|---|
| GET `/reports/financial-position` | `REPORT_VIEW_ALL` | `asOf?` (today) | `FinancialPositionView` |
| GET `/reports/income-statement` | `REPORT_VIEW_ALL` | `from?`, `to?` (1 Jan this year → today) | `IncomeStatementView` |
| GET `/reports/cash-book` | `REPORT_VIEW_ALL` | `account?` (`1000`/`1010`/`1020`; all three if omitted), `from?`, `to?` (1st of this month → today) | `CashBookView` |
| GET `/reports/contributions` | `REPORT_VIEW_ALL` | `from?`, `to?` (all periods) | `ContributionReportView` |
| GET `/reports/loan-portfolio` | `REPORT_VIEW_ALL` | — | `LoanPortfolioView` |
| GET `/reports/savings` | `REPORT_VIEW_ALL` | — | `SavingsRegisterView` |
| GET `/reports/members/{memberUserId}/statement` | `REPORT_VIEW_ALL` | `from?`, `to?` (all) | `MemberStatementView` |
| GET `/reports/members/me/statement` | active member | `from?`, `to?` (all) | `MemberStatementView` |

Errors: `REPORT_INVALID_PERIOD` (`to` before `from`), `REPORT_NOT_A_CASH_ACCOUNT`, `REPORT_MEMBER_NOT_FOUND`.

---

## 16. Audit log (`/api/v1/audit-log`)

### GET `/audit-log`
- Permission: `AUDIT_LOG_VIEW` · Query: `action?` (exact match, see `KNOWN_AUDIT_ACTIONS`), `page`, `size`
- → `200 Paged<AuditLogEntry>` (newest first). `actorUserId` is an id only: resolve names from the member list.

---

## 17. Opening-balance import (`/api/v1/imports/opening-balances`)

For a chama moving from Excel. Permission `CHAMA_SETTINGS_MANAGE` on all four. Done once per chama, all-or-nothing.

### GET `/imports/opening-balances/template`
- → `200` **binary** `.xlsx` (`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `Content-Disposition: attachment; filename="opening-balances-template.xlsx"`). Not wrapped in the JSON envelope. Fetch with the bearer header as a Blob and trigger a download. Has sheets Members, Loans, Accounts, Investments, with one savings column per savings product (create products first).

### POST `/imports/opening-balances/preview`
Checks every row; saves nothing.
- Body: `multipart/form-data` with `file` (the .xlsx, ≤ 5 MB) and `asOf` (ISO date, the cut-over day, not in the future). Do **not** set `Content-Type` manually; let the browser add the boundary.
- → `200 ImportPreviewView` — "Ready to import" or "4 problems to fix"
- Errors: `IMPORT_INVALID_FILE` (400), `PAYLOAD_TOO_LARGE` (413)

### POST `/imports/opening-balances`
- Same multipart body → `201 ImportView` — "Opening balances imported". New members are emailed a set-password link (`/reset-password?chama=…&token=…`, valid 7 days).
- Errors: `IMPORT_HAS_PROBLEMS` (422; preview to see them), `IMPORT_ALREADY_DONE` (409), `IMPORT_INVALID_FILE`

### GET `/imports/opening-balances`
- → `200 ImportView | null` (`data` is null, message "Opening balances have not been imported yet", when not done)

---

## 17a. History import (`/api/v1/imports/history`)

For a chama that ran for years before using the system: every contribution, loan, penalty, investment and expense, each on its own date. Permission `CHAMA_SETTINGS_MANAGE` on all. One Excel file per step; `{kind}` is the step's slug, in this order:

| # | `{kind}` | What it brings in |
|---|---|---|
| 1 | `setup` | Members' real join dates, and what each savings product asked per period since the chama started (back-fills the contribution periods before the chama's first period in the system; those periods are never penalised automatically) |
| 2 | `contributions` | Every payment, split across products as the system would |
| 3 | `loans` | Every loan (repaid or not) and every repayment; loans still being repaid carry on with their schedule |
| 4 | `penalties` | Penalties and fines as recorded, with payments and waivers |
| 5 | `investments` | Investments and their activity: deposits, income, withdrawals, valuations, closure |
| 6 | `expenses` | Expenses paid |
| 7 | `reconciliation` | Actual cash, bank and M-Pesa balances; differences are posted as one adjustment per date against retained surplus |

Rules: `setup` comes first and only once (undo it to redo it); the others need it and may be imported in several files. Not available once opening balances were imported (§17), and §17 is refused while any history import is in place. Nothing is emailed. Bank and M-Pesa rows without a reference get one made up (`IMP-<batch>-<row>`), with a warning.

### GET `/imports/history/{kind}/template`
- → `200` **binary** `.xlsx` (`filename="history-<n>-<kind>-template.xlsx"`), not wrapped in the envelope. A *Read me* sheet explains every column; the chama's members (and products, loan products, expense accounts, investments) are filled in or offered in drop-downs. Members are named by number (`12` or `12 - Jane Doe`) or email.

### POST `/imports/history/{kind}/preview`
Checks every row; saves nothing.
- Body: `multipart/form-data` with `file` (the .xlsx, ≤ 5 MB, ≤ 50,000 rows per sheet).
- → `200 HistoryPreviewView` — "Ready to import" or "4 problems to fix". `summary` and `tables` are ready to display (cells formatted); `problems` holds at most 1,000, errors first.
- Errors: `IMPORT_INVALID_FILE` (400), `PAYLOAD_TOO_LARGE` (413)

### POST `/imports/history/{kind}`
- Same multipart body → `201 HistoryBatchView` — "Imported". All rows or none.
- Errors: `IMPORT_HAS_PROBLEMS` (422; preview to see them), `IMPORT_NOT_ALLOWED` (409: setup missing or already done, or opening balances imported), `IMPORT_INVALID_FILE`

### GET `/imports/history/batches`
- → `200 HistoryBatchView[]`, newest first, including undone ones (`undoneAt` set).

### POST `/imports/history/batches/{id}/undo`
Only the latest batch still in place, and only while nothing recorded since depends on it (`undoable`; else `undoBlockedReason`, e.g. a repayment recorded on an imported loan). Removes what it created; ledger entries are reversed, each reversal dated as its original. Contribution payments are reversed rather than deleted.
- → `200 HistoryBatchView` — "Import undone" · Errors: `IMPORT_UNDO_NOT_ALLOWED` (409), `IMPORT_BATCH_NOT_FOUND` (404)

---

## 18. Platform administration (`/api/v1/platform/chamas`) · `ROLE_ADMIN`

Platform admins log in **without** `X-Chama-Code`. They have no chama, so chama endpoints return `TENANT_REQUIRED`.

### POST `/platform/chamas`
The only way to create a chama. Creates chama + licence (30-day TRIAL unless `license` given) + first administrator (chairperson, member #1, emailed a verification link).
- Body: `PlatformOnboardChamaRequest`
```json
{ "chama": { "name": "Mokica Investment Group", "code": "mokica", "currency": "KES",
             "admin": { "email": "chair@mokica.co.ke", "phoneNumber": "0712345678", "password": "Start1234",
                        "firstName": "Grace", "lastName": "Achieng" } },
  "license": { "plan": "STANDARD", "validUntil": "2027-12-31T21:00:00Z" } }
```
- → `201 OnboardingResponse` — "Chama registered. Log in with chama code 'mokica'; …"
- Errors: `CHAMA_CODE_TAKEN` (409), `CHAMA_INVALID_CODE`, `CHAMA_INVALID_NAME`, `CHAMA_INVALID_SETTINGS`, `LICENSE_INVALID_PERIOD`, `LICENSE_INVALID_MEMBER_LIMIT`, password/phone errors

### GET `/platform/chamas`
- Query: `page`, `size` (sorted by name) → `200 Paged<PlatformChamaView>`

### GET `/platform/chamas/{chamaId}`
- → `200 PlatformChamaView` · Errors: `CHAMA_NOT_FOUND`

### PUT `/platform/chamas/{chamaId}/license`
Renew, upgrade, suspend, reactivate. Only sent fields change.
- Body: `UpdateLicenseRequest`, e.g. renew `{ "validUntil": "2027-12-31T21:00:00Z" }`, suspend `{ "status": "SUSPENDED", "notes": "Invoice 2026-11 unpaid" }`
- → `200 LicenseView` — "Licence updated"
- Errors: `LICENSE_INVALID_PERIOD`, `LICENSE_INVALID_MEMBER_LIMIT`, `LICENSE_INVALID_NOTES`, `CHAMA_NOT_FOUND`

---

## 19. Operational endpoints

| Endpoint | Auth | Use |
|---|---|---|
| GET `/actuator/health` | public | `{"status":"UP"}`; use for a "server unreachable" banner |
| GET `/actuator/info` | public | build info |

---

## 20. Errors that any endpoint can return

| HTTP | `error.code` | When | Frontend handling |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean validation; `error.fields[]` lists each input | Map `fields[].field` to form fields; show `message` as a summary |
| 400 | `MALFORMED_REQUEST` | Body missing / not JSON | Bug: log it |
| 400 | `INVALID_PARAMETER` / `MISSING_PARAMETER` | Bad query/path value | Bug or bad URL: show message |
| 400 | `TENANT_REQUIRED` | Public endpoint without `X-Chama-Code`, or platform admin calling a chama endpoint | Ask for chama code |
| 401 | `UNAUTHENTICATED` | No/unknown credentials on a protected endpoint | Refresh once, else to login |
| 401 | `AUTH_INVALID_ACCESS_TOKEN` | Expired or invalid bearer token | Refresh once and retry |
| 403 | `ACCESS_DENIED` | Missing chama permission, inactive membership, or not admin | Re-fetch `/members/me`; show "You don't have permission" |
| 403 | `LICENSE_EXPIRED` / `LICENSE_SUSPENDED` | Licence unusable | Route to the licence-blocked screen |
| 403 | `LICENSE_FEATURE_NOT_INCLUDED` | Feature not in plan | Hide the module; show upgrade hint |
| 404 | `NOT_FOUND` / `TENANT_NOT_FOUND` / `*_NOT_FOUND` | Missing resource or unknown chama code | Not-found state |
| 405/406/415 | `METHOD_NOT_ALLOWED` / `NOT_ACCEPTABLE` / `UNSUPPORTED_MEDIA_TYPE` | Client bug | Log |
| 409 | `CONCURRENT_MODIFICATION` | Someone else changed the record | Refetch, ask the user to retry |
| 409 | `DATA_CONFLICT` | DB uniqueness race | Show message, refetch |
| 413 | `PAYLOAD_TOO_LARGE` | Upload > 5 MB | "File too large (max 5 MB)" |
| 422 | various `*_` business codes | Business rule | Show `message` (it is user-friendly) |
| 429 | `AUTH_ACCOUNT_LOCKED` / `TOO_MANY_REQUESTS` | Throttled | Countdown from `retryAfterSeconds` |
| 500 | `INTERNAL_ERROR` | Server bug | Generic error toast; allow retry |
| 503 | `SERVICE_UNAVAILABLE` | Down for maintenance | Banner, retry with backoff |
