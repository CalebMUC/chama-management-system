# Chama Platform: frontend build guide

This folder is the full specification for building the web frontend of the Chama Management
Platform against its existing Spring Boot API. It is written for the engineer (or coding agent)
building that frontend. Nothing in the backend needs to change for the screens described here,
except where [§12 Known gaps](#12-known-backend-gaps-and-workarounds) says so.

| File | What it gives you |
|---|---|
| `README.md` (this file) | Domain primer, access model, session handling, API client rules, screen-by-screen spec, UX rules, build order, acceptance checklist |
| [`API_REFERENCE.md`](API_REFERENCE.md) | Every endpoint: method, path, permission, licence feature, body, response, error codes |
| [`api-types.ts`](api-types.ts) | TypeScript types for every request, response and enum. Copy it into the frontend as `src/api/types.ts` |
| [`DASHBOARDS.md`](DASHBOARDS.md) | The chama overview, member home, top members, top investments and trends screens: fields, layout, states, caching |

Source of truth when in doubt: the running backend's OpenAPI document at `GET /v3/api-docs`
(Swagger UI at `/swagger-ui.html`), and the code under `backend/src/main/java/ke/chama/modules/*/presentation`.

---

## Contents

1. [The product in one page](#1-the-product-in-one-page)
2. [Who uses it: access model](#2-who-uses-it-access-model)
3. [Talking to the API](#3-talking-to-the-api)
4. [Authentication and sessions](#4-authentication-and-sessions)
5. [App start-up and routing guard](#5-app-start-up-and-routing-guard)
6. [Routes and navigation](#6-routes-and-navigation)
7. [Screen specifications](#7-screen-specifications)
8. [Cross-cutting UX rules](#8-cross-cutting-ux-rules)
9. [Data refresh after changes](#9-data-refresh-after-changes)
10. [Formatting reference](#10-formatting-reference)
11. [Running the backend locally](#11-running-the-backend-locally)
12. [Known backend gaps and workarounds](#12-known-backend-gaps-and-workarounds)
13. [Recommended stack and project layout](#13-recommended-stack-and-project-layout)
14. [Build order](#14-build-order)
15. [Acceptance checklist](#15-acceptance-checklist)

---

## 1. The product in one page

A **chama** (Swahili, "group") is a savings and investment group, common in Kenya: members pay a
fixed contribution every month (or week), the pooled money is lent back to members and invested,
and officials run the books. This platform replaces the WhatsApp-and-notebook way of doing that.

It is **multi-tenant SaaS**: one deployment hosts many chamas. Each chama is fully isolated, has
a licence (plan + expiry + feature list), and is identified by a short **chama code** such as
`mokica`. A person's account belongs to exactly one chama.

What the platform manages, and the module that does it:

| Area | What happens | Who acts |
|---|---|---|
| **Membership** | People register with the chama code, an official approves them, they get a member number. Officials suspend/reactivate members and assign roles. | Applicant, secretary, chairperson |
| **Contributions** | Each period (e.g. month `2026-11`) every member owes the contribution. Officials record payments (cash, bank, M-Pesa with its transaction code). The system computes arrears, advances and late periods. | Treasurer records; member views own |
| **Savings products** | Pots members save into: Deposits (default), Share capital, Welfare, custom. A payment is split across products by priority. Each member has an account per product with a statement. | Treasurer configures |
| **Penalties** | Late contributions are penalised automatically (a % of the contribution). Officials also fine members, record penalty payments and waive penalties. | Treasurer, chairperson |
| **Loans** | A member checks eligibility (a multiple of savings), applies under a loan product, names guarantors, who accept or decline. A quorum of officials approves. The treasurer disburses and records repayments against a monthly schedule. | Member, guarantors, officials |
| **Expenses** | An official records an expense; a quorum of *other* officials approves it; then it is paid out through the books. | Treasurer creates; chair/secretary approve |
| **Investments** | Proposed, approved by quorum, funded, then income, top-ups, withdrawals and valuations are recorded until it is closed. Returns and profitability are computed. | Treasurer manages; chair/secretary approve |
| **Ledger** | Real double-entry books behind every money movement. Chart of accounts, journal, statements, trial balance, manual entries and reversals. | Treasurer; auditor views |
| **Reports & dashboards** | Balance sheet, income statement, cash book, contribution matrix, loan ageing (PAR30), savings register, member statements. | Officials; members see their own |
| **Audit log** | Append-only "who did what". | Secretary, auditor, chairperson |
| **Opening-balance import** | A chama moving from Excel uploads its members, balances, loans and investments once. | Chairperson |
| **Platform admin** | The platform operator onboards chamas and manages their licences. | Platform administrator |

Two principles show up everywhere and must be visible in the UI:

- **Separation of duties.** People never act on their own money: you cannot record your own
  contribution, approve your own loan, approve an expense you recorded, and so on. Approvals need a
  quorum (default 2) of *distinct* officials.
- **Nothing is deleted.** Money records are reversed, rejected, cancelled or waived, always with a
  reason, and every step is in the audit log.

### Glossary

| Term | Meaning |
|---|---|
| Chama code | Unique, permanent, lowercase login code of a chama (`mokica`). Sent as `X-Chama-Code` on public auth calls. |
| Member number | Sequential number assigned on approval (`#12`). |
| Official | A member whose roles grant management permissions (treasurer, secretary, chairperson). |
| Period | One contribution cycle: key `2026-11` (monthly) or `2026-W45` (weekly), with a due date. |
| Arrears | Dues whose due date has passed and that are unpaid. |
| Advance | Paid beyond every due so far; covers future periods. |
| Late | Unpaid (or part-paid) after the due date plus the grace days. |
| Seated member | ACTIVE or SUSPENDED (still has money in the chama). |
| Quorum | `approvalQuorum` setting: distinct officials needed to approve a loan, expense or investment. |
| Basis points (bp) | Integer rates: 100 bp = 1%. |
| Minor units | Integer money: KES cents. `500000` = KES 5,000.00. |
| PAR30 | Portfolio at risk: share of outstanding loans more than 30 days overdue. |
| Book value / market value | What an investment cost (books) vs. its latest valuation. |

---

## 2. Who uses it: access model

### 2.1 Kinds of user

| User | How they sign in | What they see |
|---|---|---|
| **Platform administrator** | `/admin/login`, email + password, **no chama code**. `GET /auth/me` returns `roles` containing `ROLE_ADMIN` and `chamaId: null`. | Platform console only (chamas, licences). Chama endpoints return `TENANT_REQUIRED`. |
| **Applicant** | Registered with a chama code; membership `PENDING_APPROVAL`. | A waiting screen. No permissions. |
| **Member** | Chama code + email + password; membership `ACTIVE`. | Their own dashboard, savings, contributions, loans, penalties, statement. Plus whatever their roles' permissions allow. |
| **Suspended / rejected / exited member** | Can still log in. `permissions` is empty. | A status screen explaining their state (show `suspensionReason`). |

### 2.2 Tenancy and the chama code

- Accounts are per chama. The same email can exist in two chamas as two separate accounts, so
  **login needs the chama code**.
- Public auth endpoints (`register`, `login`, `refresh`, `logout`, `email/verify`, `email/verify/resend`,
  `password/forgot`, `password/reset`) must send `X-Chama-Code: <code>`. Without it the request is
  treated as the platform tenant (that is how platform admins log in).
- Every logged-in request takes the chama from the access token. Never send `X-Chama-Code` with a bearer token; it is ignored.
- Emailed links carry the code: `…/verify-email?chama=mokica&token=…`.
- Remember the last chama code on the device (`localStorage`) and prefill the login form.
- The code is case-insensitive on the way in; display it lowercase.

### 2.3 Roles and permissions

A member holds one or more **chama roles**: `MEMBER` (always), `TREASURER`, `SECRETARY`,
`CHAIRPERSON`, `AUDITOR`. Roles map to **permissions**, and **each chama can change that mapping**
(Settings → Roles & permissions). Therefore:

> **Gate every screen, menu item and button on `permissions` from `GET /members/me`, never on role names.**

Permissions are re-read by the server on every request, so a role change or suspension takes
effect immediately. Re-fetch `/members/me` when the app regains focus, after any `403 ACCESS_DENIED`,
and after the user changes roles/permissions.

Default mapping for a new chama (✓ = granted):

| Permission | MEMBER | TREASURER | SECRETARY | CHAIRPERSON | AUDITOR | Unlocks |
|---|:-:|:-:|:-:|:-:|:-:|---|
| `CHAMA_VIEW` | ✓ | ✓ | ✓ | ✓ | ✓ | Chama profile, licence |
| `CHAMA_SETTINGS_MANAGE` | | | | ✓ | | Chama settings, opening-balance import |
| `MEMBER_VIEW` | | ✓ | ✓ | ✓ | ✓ | Member directory, roles list |
| `MEMBER_INVITE` | | | ✓ | ✓ | | Invite by email |
| `MEMBER_APPROVE` | | | | ✓ | | Approve/reject applications |
| `MEMBER_SUSPEND` | | | | ✓ | | Suspend/reactivate |
| `MEMBER_ROLES_MANAGE` | | | | ✓ | | Change a member's roles |
| `ROLE_PERMISSIONS_MANAGE` | | | | ✓ | | Edit role → permission mapping |
| `CONTRIBUTION_VIEW_ALL` | | ✓ | ✓ | ✓ | ✓ | All payments, periods, standings |
| `CONTRIBUTION_RECORD` | | ✓ | | ✓ | | Record a payment |
| `CONTRIBUTION_REVERSE` | | ✓ | | ✓ | | Reverse a payment |
| `LOAN_APPLY` | ✓ | ✓ | ✓ | ✓ | ✓ | Eligibility, apply |
| `LOAN_VIEW_ALL` | | ✓ | ✓ | ✓ | ✓ | Loan book |
| `LOAN_APPROVE` | | ✓ | | ✓ | | Approve/reject loans |
| `LOAN_DISBURSE` | | ✓ | | ✓ | | Disburse |
| `LOAN_REPAYMENT_RECORD` | | ✓ | | ✓ | | Record repayments |
| `PENALTY_VIEW_ALL` | | ✓ | ✓ | ✓ | ✓ | All penalties |
| `PENALTY_RECORD` | | ✓ | | ✓ | | Fine, record payment, run assessment |
| `PENALTY_WAIVE` | | | | ✓ | | Waive |
| `EXPENSE_VIEW` | ✓ | ✓ | ✓ | ✓ | ✓ | Expenses list/summary |
| `EXPENSE_CREATE` | | ✓ | | ✓ | | Record, cancel, pay |
| `EXPENSE_APPROVE` | | | ✓ | ✓ | | Approve/reject |
| `INVESTMENT_VIEW` | ✓ | ✓ | ✓ | ✓ | ✓ | Investments, portfolio |
| `INVESTMENT_MANAGE` | | ✓ | | ✓ | | Propose, fund, record activity, close |
| `INVESTMENT_APPROVE` | | | ✓ | ✓ | | Approve/reject |
| `LEDGER_VIEW` | | ✓ | | ✓ | ✓ | Chart, journal, statements, trial balance |
| `LEDGER_POST` | | ✓ | | ✓ | | Manual entries, reversals |
| `LEDGER_ACCOUNTS_MANAGE` | | ✓ | | ✓ | | Add/deactivate accounts |
| `REPORT_VIEW_ALL` | | ✓ | ✓ | ✓ | ✓ | Chama dashboard, all reports |
| `AUDIT_LOG_VIEW` | | | ✓ | ✓ | ✓ | Audit log |
| `PRODUCTS_MANAGE` | | ✓ | | ✓ | | Savings and loan products |
| `MEMBER_ACCOUNTS_VIEW_ALL` | | ✓ | ✓ | ✓ | ✓ | Every member's accounts |
| `MEMBER_ACCOUNTS_MANAGE` | | ✓ | | ✓ | | Open/close/reopen accounts |
| `PAYMENT_SUBMIT` | ✓ | ✓ | ✓ | ✓ | ✓ | Report my own contribution, penalty payment or loan repayment for the treasurer to confirm |

`CHAMA_VIEW` also opens the members' chama overview (`GET /dashboard/overview`). Reviewing reported
payments needs the matching record permission (`CONTRIBUTION_RECORD`, `PENALTY_RECORD`,
`LOAN_REPAYMENT_RECORD`), so by default the treasurer and chairperson review them.

A member's effective permissions are the union over their roles. Things that need **no**
permission, only an ACTIVE membership: everything under "My …" (`/…/me` endpoints), loan products
list, savings products list, guarantee requests and answers, cancelling one's own loan application.

The CHAIRPERSON role can never lose `MEMBER_ROLES_MANAGE` and `ROLE_PERMISSIONS_MANAGE`, and a
chama must always keep at least one active chairperson.

### 2.4 Licence

Every chama has one licence. Read it with `GET /chama/license` (`LicenseView`).

| Plan | Default members | Default features |
|---|---|---|
| TRIAL (30 days on onboarding) | 25 | all |
| BASIC | 30 | CONTRIBUTIONS, PENALTIES, EXPENSES, REPORTS |
| STANDARD | 100 | + LOANS, INVESTMENTS, SMS_NOTIFICATIONS |
| PREMIUM | 1000 | all |

A platform admin can override the member limit and features per chama.

Feature → API areas it unlocks:

| Feature | Endpoints |
|---|---|
| `CONTRIBUTIONS` | `/contributions/**`, `/savings-products/**`, `/member-accounts/**` |
| `PENALTIES` | `/penalties/**` |
| `LOANS` | `/loans/**`, `/loan-products/**` |
| `EXPENSES` | `/expenses/**` |
| `INVESTMENTS` | `/investments/**` |
| `REPORTS` | `/reports/**` |
| `MPESA`, `SMS_NOTIFICATIONS` | No endpoints yet; ignore in the UI |
| (always) | `/auth`, `/chama`, `/members`, `/roles`, `/ledger`, `/dashboard`, `/audit-log`, `/imports` |

Licence **state**:

- `ACTIVE`: normal. If `daysRemaining <= 14`, show a dismissible banner to users holding
  `CHAMA_SETTINGS_MANAGE` ("Your licence expires in N days").
- `EXPIRED` or `SUSPENDED`: every chama request except `GET /chama`, `GET /chama/license` and
  `GET /members/me` returns `403 LICENSE_EXPIRED` / `LICENSE_SUSPENDED`. Route to a
  **Licence blocked** screen showing plan, `validUntil`, `notes`, and the server `message`.
  Auth endpoints (logout, change password) keep working.

Hide navigation for features the licence lacks. If a request still returns
`403 LICENSE_FEATURE_NOT_INCLUDED`, show "Your chama's plan does not include X" with no retry.

---

## 3. Talking to the API

### 3.1 Base URL and CORS

- API base: `http://localhost:8080` locally. Paths start with `/api/v1`.
- **The backend has no CORS configuration.** A browser app on another origin will be blocked.
  Serve the frontend through a same-origin proxy:
  - Development: dev-server proxy of `/api` and `/actuator` to `http://localhost:8080`
    (Vite: `server.proxy`), and call relative URLs (`/api/v1/...`).
  - Production: put the SPA and the API behind the same reverse proxy (nginx/ingress) on one origin.
  - Make the base URL configurable (`VITE_API_BASE_URL`, default empty string = same origin).
- **Run the dev server on port 3000.** The backend's emailed links default to
  `http://localhost:3000` (`FRONTEND_BASE_URL`).

### 3.2 Headers

| Header | When |
|---|---|
| `Content-Type: application/json` | Every request with a JSON body. Not for multipart uploads (let the browser set the boundary). |
| `Accept: application/json` | Every request (except the template download). |
| `Authorization: Bearer <accessToken>` | Every protected request. **Never** on the public auth endpoints: an expired token there makes the server answer 401 even though the endpoint is public. |
| `X-Chama-Code: <code>` | Public auth endpoints, for chama users. Omit for platform admins. |

### 3.3 The response envelope

Every response, success or failure (including 401/403/404 produced by the framework), is:

```json
{ "success": true,  "message": "Contribution recorded", "data": { … }, "timestamp": "2026-11-08T07:15:02Z" }
{ "success": true,  "message": "OK", "data": [ … ], "meta": { "page": 0, "size": 50, "totalElements": 73, "totalPages": 2, "hasNext": true, "hasPrevious": false }, "timestamp": "…" }
{ "success": false, "message": "One or more fields are invalid", "data": null,
  "error": { "code": "VALIDATION_FAILED", "status": 400, "path": "/api/v1/contributions",
             "fields": [ { "field": "amountMinor", "message": "must be greater than 0" } ] },
  "timestamp": "…" }
```

Rules:

1. Check `success`. On success use `data` (and `meta` on paged lists).
2. `message` is always written for end users. Show it in a success toast after mutations
   (e.g. "Approval recorded (1 of 2)"), and in error toasts/alerts.
3. Branch on `error.code`, never on `message` wording.
4. `error.fields` maps to form fields by JSON path (`email`, `settings.loanMaxMultiplier`,
   `lines[1].accountCode`, `allocations[0].amountMinor`). Show each next to its input; if a field
   is not on screen, show it in the form's error summary.
5. On 429, use `error.retryAfterSeconds` (also the `Retry-After` header) for a countdown.
6. The one non-envelope response is the `.xlsx` template download.
7. If the body is not JSON (proxy error, server down), show "Can't reach the server. Check your
   connection and try again."

### 3.4 Paging

Lists that page take `?page=0&size=50` (zero-based; size max 100) and return `meta`. Use
`meta.hasNext` for "Load more"/pagination. Non-paged lists (periods, standings, products,
accounts, reports) return the whole array.

### 3.5 Data conventions

| Kind | Wire format | Frontend rule |
|---|---|---|
| Money | Integer minor units, field names end in `Minor` | Never use floats for arithmetic. Parse user input with string math (see §10). Display with the chama's `currency`. |
| Rates | Integer basis points, names end in `BasisPoints` | `bp / 100` + `%` for display; `Math.round(percent * 100)` for input. |
| Dates | `"2026-11-08"` | Business dates are days in **Africa/Nairobi**. Don't convert them with `new Date()` (UTC shift). Treat as plain strings; format for display. |
| Instants | `"2026-11-06T09:00:00Z"` (UTC, maybe fractional seconds) | Parse, show in local time ("6 Nov 2026, 12:00"). |
| Enums | UPPER_SNAKE strings | Map to human labels (§10). Unknown values: show title-cased. |
| Nulls | Keys present with `null` | Render "—". |
| IDs | UUID strings | Two member ids exist: `membershipId` (member admin endpoints) and `userId` / `memberUserId` (everything financial). |

"Not in the future" date fields (payment dates, disbursement, expenses, entries…) default to
today in Nairobi when omitted; set the date picker's `max` to today in Nairobi.

---

## 4. Authentication and sessions

### 4.1 Tokens

- Login returns `AuthResponse`: a JWT **access token** (15 min) and an opaque **refresh token**
  (7 days, single use).
- The access token's claims are not needed by the UI; use `GET /auth/me` and `GET /members/me`.
- **Storage:** keep the access token in memory. Keep the session record
  `{ refreshToken, refreshTokenExpiresAt, accessToken, accessTokenExpiresAt, chamaCode, isPlatformAdmin }`
  in `localStorage` (or `sessionStorage` when the user unticks "Keep me signed in"). The backend
  has no cookie-based session, so the refresh token must be readable by JS: keep third-party
  scripts out of the app and use a strict Content-Security-Policy in production.
- **Store the chama code with the session.** Refresh and logout must send it as `X-Chama-Code`;
  a refresh without it fails with `AUTH_INVALID_TOKEN` and logs the user out.

### 4.2 Login flows

**Member login** (`/login`): fields Chama code, Email, Password, "Keep me signed in".
`POST /auth/login` with `X-Chama-Code`. Errors:
- `AUTH_INVALID_CREDENTIALS` → "Invalid email or password" (do not say which).
- `TENANT_NOT_FOUND` → error on the chama-code field.
- `AUTH_ACCOUNT_LOCKED` → show message and a countdown from `retryAfterSeconds`; offer "Forgot password?".
- `AUTH_ACCOUNT_DISABLED`, `AUTH_EMAIL_NOT_VERIFIED` → show message; for the latter offer "Resend verification email".

**Platform admin login** (`/admin/login`): Email, Password, **no** chama header. After login,
`/auth/me` must show `ROLE_ADMIN`; otherwise log out and show "This sign-in is for platform administrators".

### 4.3 Refresh

- Refresh proactively about 60 s before `accessTokenExpiresAt`, and reactively when a protected
  call returns `401` (`AUTH_INVALID_ACCESS_TOKEN` or `UNAUTHENTICATED`), then retry that call once.
- `POST /auth/refresh` with `{ refreshToken }`, `X-Chama-Code: <stored code>` (none for admins),
  **no** `Authorization` header. Save **both** new tokens.
- If refresh fails (`401 AUTH_INVALID_TOKEN`, `403 AUTH_ACCOUNT_DISABLED`), clear the session,
  cancel queued requests, and go to login with "Your session has ended. Please sign in again."

### 4.4 Refresh must never run twice at once (critical)

Refresh tokens rotate. If the server sees a refresh token that was **already exchanged**, it
assumes theft and **revokes every session of that user on every device**. That happens if two
refreshes race: two API calls getting 401 together, or two browser tabs refreshing together.

Required behaviour:

1. **One refresh per tab:** keep a single in-flight refresh promise; every request that needs a
   fresh token awaits it.
2. **One refresh per browser:** wrap the refresh in a cross-tab lock
   (`navigator.locks.request('chama-session-refresh', …)`). Inside the lock, **re-read the session
   from storage first**: if another tab already refreshed (stored access token differs from yours
   and is not expiring), adopt it instead of refreshing again.
3. Broadcast session changes (login, refresh, logout) to other tabs (`BroadcastChannel` or the
   `storage` event) so they update their in-memory token or log out too.

### 4.5 Other session actions

| Action | Call | Then |
|---|---|---|
| Log out | `POST /auth/logout` `{ refreshToken }` + `X-Chama-Code`, no bearer | Clear session, broadcast, go to `/login`. Clear local state even if the call fails. |
| Log out everywhere | `POST /auth/logout-all` (bearer) | Same as log out. |
| Change password | `POST /auth/password/change` (bearer) | Response has a **new token pair**: store it. Other devices are logged out. |
| Reset password (from email) | `POST /auth/password/reset` | All sessions end; send the user to `/login` with the chama code prefilled. |

### 4.6 Reference implementation

A minimal TypeScript client showing the rules above. Adapt to your HTTP library.

```ts
// src/api/client.ts
import type { ApiSuccess, ApiFailure, AuthResponse, FieldError } from './types';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';
const PUBLIC_AUTH = new Set([
  '/api/v1/auth/register', '/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout',
  '/api/v1/auth/email/verify', '/api/v1/auth/email/verify/resend',
  '/api/v1/auth/password/forgot', '/api/v1/auth/password/reset',
]);

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fields: FieldError[] = [],
    readonly retryAfterSeconds?: number,
  ) { super(message); }
}

// ---- session ------------------------------------------------------------------------------
export interface StoredSession {
  accessToken: string;
  accessTokenExpiresAt: number; // epoch ms
  refreshToken: string;
  refreshTokenExpiresAt: number;
  chamaCode: string | null;     // null for platform admins
}
const KEY = 'chama.session';
const channel = new BroadcastChannel('chama-session');
let current: StoredSession | null = load();

function load(): StoredSession | null {
  const raw = localStorage.getItem(KEY) ?? sessionStorage.getItem(KEY);
  return raw ? (JSON.parse(raw) as StoredSession) : null;
}
export function saveSession(auth: AuthResponse, chamaCode: string | null, persistent = true) {
  current = {
    accessToken: auth.accessToken,
    accessTokenExpiresAt: Date.parse(auth.accessTokenExpiresAt),
    refreshToken: auth.refreshToken,
    refreshTokenExpiresAt: Date.parse(auth.refreshTokenExpiresAt),
    chamaCode,
  };
  (persistent ? localStorage : sessionStorage).setItem(KEY, JSON.stringify(current));
  channel.postMessage('changed');
}
export function clearSession() {
  current = null;
  localStorage.removeItem(KEY);
  sessionStorage.removeItem(KEY);
  channel.postMessage('cleared');
}
channel.onmessage = () => { current = load(); /* if null: route to /login */ };

// ---- refresh: single-flight per tab, locked across tabs ------------------------------------------
let inFlight: Promise<boolean> | null = null;

export function refreshSession(): Promise<boolean> {
  inFlight ??= navigator.locks.request('chama-session-refresh', async () => {
    const latest = load();
    if (!latest) return false;
    // Another tab refreshed while we waited for the lock: adopt its tokens.
    if (latest.accessToken !== current?.accessToken && latest.accessTokenExpiresAt - Date.now() > 30_000) {
      current = latest;
      return true;
    }
    const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'application/json' };
    if (latest.chamaCode) headers['X-Chama-Code'] = latest.chamaCode;
    const res = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
      method: 'POST', headers, body: JSON.stringify({ refreshToken: latest.refreshToken }),
    });
    const body = await res.json().catch(() => null);
    if (!res.ok || !body?.success) { clearSession(); return false; }
    saveSession(body.data as AuthResponse, latest.chamaCode, localStorage.getItem(KEY) !== null);
    return true;
  }).finally(() => { inFlight = null; });
  return inFlight;
}

async function freshAccessToken(): Promise<string | null> {
  if (!current) return null;
  if (current.accessTokenExpiresAt - Date.now() < 60_000) await refreshSession();
  return current?.accessToken ?? null;
}

// ---- requests ------------------------------------------------------------------------------
type Query = Record<string, string | number | boolean | undefined | null>;
interface Options { query?: Query; body?: unknown; chamaCode?: string | null; signal?: AbortSignal }

export async function api<T>(method: string, path: string, opts: Options = {}, retried = false): Promise<ApiSuccess<T>> {
  const isPublic = PUBLIC_AUTH.has(path);
  const headers: Record<string, string> = { Accept: 'application/json' };
  const isForm = opts.body instanceof FormData;
  if (opts.body !== undefined && !isForm) headers['Content-Type'] = 'application/json';
  if (isPublic) {
    const code = opts.chamaCode !== undefined ? opts.chamaCode : current?.chamaCode;
    if (code) headers['X-Chama-Code'] = code;
  } else {
    const token = await freshAccessToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }

  const url = new URL(`${API_BASE}${path}`, window.location.origin);
  Object.entries(opts.query ?? {}).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v)); });

  let res: Response;
  try {
    res = await fetch(url, {
      method, headers, signal: opts.signal,
      body: opts.body === undefined ? undefined : isForm ? (opts.body as FormData) : JSON.stringify(opts.body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', "Can't reach the server. Check your connection and try again.");
  }
  const body = (await res.json().catch(() => null)) as ApiSuccess<T> | ApiFailure | null;

  if (res.ok && body?.success) return body;

  if (res.status === 401 && !isPublic && !retried && current) {
    if (await refreshSession()) return api<T>(method, path, opts, true);
  }
  const err = body && !body.success ? body.error : undefined;
  throw new ApiError(
    res.status,
    err?.code ?? 'UNKNOWN_ERROR',
    body?.message ?? 'Something went wrong. Please try again.',
    err?.fields ?? [],
    err?.retryAfterSeconds,
  );
}
```

---

## 5. App start-up and routing guard

```
App load
 ├─ No stored session ─────────────────────────────────────────► public routes (/login …)
 └─ Stored session
     ├─ refresh token expired ─► clear ─────────────────────────► /login
     └─ GET /auth/me  (refreshes first if the access token is stale)
         ├─ roles ∋ ROLE_ADMIN and chamaId = null ──────────────► /platform
         └─ chama user ─► GET /members/me
             ├─ status PENDING_APPROVAL ────────────────────────► /pending
             ├─ status REJECTED | SUSPENDED | EXITED ───────────► /membership-status
             └─ status ACTIVE ─► GET /chama/license (+ GET /chama)
                 ├─ state EXPIRED | SUSPENDED ──────────────────► /licence-blocked
                 └─ ACTIVE ─────────────────────────────────────► app shell (/home)
```

Keep these in a session store available everywhere:

```ts
interface AppContext {
  user: UserResponse;
  membership: MemberView | null;       // null for platform admins
  permissions: Set<ChamaPermission>;
  chama: ChamaResponse | null;
  licence: LicenseView | null;
  features: Set<LicenseFeature>;
  can(p: ChamaPermission): boolean;
  hasFeature(f: LicenseFeature): boolean;
}
```

- `GET /chama` and `GET /chama/license` need `CHAMA_VIEW`, which every member has by default but a
  chama could remove. If either returns 403 `ACCESS_DENIED`, fall back to `GET /dashboard/me`
  (`features`, `chamaName`, `currency`) and treat the licence as active.
- Route guards: `requirePermission(p)`, `requireFeature(f)`, `requireActiveMember`, `requirePlatformAdmin`.
  A guarded route the user can't open shows a "No access" page, not a blank screen.
- Global error hook: `LICENSE_EXPIRED` / `LICENSE_SUSPENDED` anywhere → re-fetch licence, go to
  `/licence-blocked`. `ACCESS_DENIED` → re-fetch `/members/me` (permissions may have changed).

---

## 6. Routes and navigation

### 6.1 Public routes

| Route | Purpose | Notes |
|---|---|---|
| `/login?chama=` | Member sign-in | Prefill chama code from query, else from last use |
| `/admin/login` | Platform admin sign-in | No chama code |
| `/register?chama=` | Join a chama | Fields: chama code, first/last name, email, phone, password, confirm |
| `/join?chama=` | **Invitation email link** | Redirect to `/register?chama=…` |
| `/verify-email?chama=&token=` | **Verification email link** | Auto-posts on load; shows success/failure |
| `/forgot-password?chama=` | Request reset email | Always shows the neutral 202 message |
| `/reset-password?chama=&token=` | **Reset / first-password email link** | Also used by imported members (title: "Set your password") |
| `/` | Redirect by session state | The approval email links here |

The four **bold** routes are opened from emails the backend sends; their paths and query
parameter names are fixed by the backend.

### 6.2 Chama app (member and officials)

Navigation groups. Show an item only if its permission and feature checks pass. Hide a group that ends up empty.

| Group | Item | Route | Permission | Feature |
|---|---|---|---|---|
| **Me** | Home | `/home` | active member | — |
| | My savings | `/me/savings`, `/me/savings/:accountId` | active member | CONTRIBUTIONS |
| | My contributions | `/me/contributions` | active member | CONTRIBUTIONS |
| | My loans | `/me/loans`, `/me/loans/apply` | active member (apply: `LOAN_APPLY`) | LOANS |
| | Guarantee requests | `/me/guarantees` | active member | LOANS |
| | My penalties | `/me/penalties` | active member | PENALTIES |
| | My statement | `/me/statement` | active member | REPORTS |
| | Report a payment | `/me/payments/new` | `PAYMENT_SUBMIT` | the paid item's feature |
| | My reported payments | `/me/payments` | active member | — |
| **Chama** | Overview (dashboard) | `/chama` | `CHAMA_VIEW` (`REPORT_VIEW_ALL` gets the full officials' version) | — |
| | Members | `/members`, `/members/:userId` | `MEMBER_VIEW` | — |
| | Expenses | `/expenses`, `/expenses/:id` | `EXPENSE_VIEW` | EXPENSES |
| | Investments | `/investments`, `/investments/portfolio`, `/investments/:id` | `INVESTMENT_VIEW` | INVESTMENTS |
| **Money** | Contributions | `/contributions` (payments), `/contributions/periods`, `/contributions/periods/:id`, `/contributions/standings` | `CONTRIBUTION_VIEW_ALL` | CONTRIBUTIONS |
| | Record contribution | `/contributions/new` | `CONTRIBUTION_RECORD` | CONTRIBUTIONS |
| | Payments to confirm | `/payment-submissions` | any of `CONTRIBUTION_RECORD`, `PENALTY_RECORD`, `LOAN_REPAYMENT_RECORD` | — |
| | Member accounts | `/member-accounts`, `/member-accounts/:id` | `MEMBER_ACCOUNTS_VIEW_ALL` | CONTRIBUTIONS |
| | Loans | `/loans`, `/loans/:id` | `LOAN_VIEW_ALL` | LOANS |
| | Penalties | `/penalties`, `/penalties/:id` | `PENALTY_VIEW_ALL` | PENALTIES |
| **Books** | Chart of accounts | `/ledger/accounts`, `/ledger/accounts/:id` | `LEDGER_VIEW` | — |
| | Journal | `/ledger/journal`, `/ledger/journal/:id`, `/ledger/journal/new` (`LEDGER_POST`) | `LEDGER_VIEW` | — |
| | Trial balance | `/ledger/trial-balance` | `LEDGER_VIEW` | — |
| | Reports | `/reports/*` | `REPORT_VIEW_ALL` | REPORTS |
| | Audit log | `/audit-log` | `AUDIT_LOG_VIEW` | — |
| **Settings** | Chama profile & rules | `/settings/chama` | view: `CHAMA_VIEW`; edit: `CHAMA_SETTINGS_MANAGE` | — |
| | Licence | `/settings/licence` | `CHAMA_VIEW` | — |
| | Roles & permissions | `/settings/roles` | `MEMBER_VIEW`; edit: `ROLE_PERMISSIONS_MANAGE` | — |
| | Savings products | `/settings/savings-products` | active member; edit: `PRODUCTS_MANAGE` | CONTRIBUTIONS |
| | Loan products | `/settings/loan-products` | active member; edit: `PRODUCTS_MANAGE` | LOANS |
| | Import opening balances | `/settings/import` | `CHAMA_SETTINGS_MANAGE` | — |
| **Account** (user menu) | Profile & security | `/profile` | any signed-in user | — |
| | Sign out / sign out everywhere | — | — | — |

Status routes: `/pending`, `/membership-status`, `/licence-blocked`.

Show a **work-queue badge** on the relevant nav items for officials, from `GET /dashboard`
`pending` (applications, loans awaiting approval/disbursement, expenses awaiting approval/payment,
investments awaiting approval, `paymentsAwaitingReview` on "Payments to confirm") and for every member from `GET /dashboard/me` `loans.guaranteeRequests`.

### 6.3 Platform console

| Route | Purpose |
|---|---|
| `/platform` | Chamas list (paged, sorted by name) with licence state, plan, expiry, member limit |
| `/platform/chamas/new` | Onboard a chama |
| `/platform/chamas/:id` | Chama detail + licence management |

---

## 7. Screen specifications

Each screen lists its data, actions and rules. Endpoint details are in [API_REFERENCE.md](API_REFERENCE.md).
Every list needs loading, empty and error states. Every mutation shows the server `message` as a
toast on success and refreshes the affected data (§9).

### 7.1 Auth screens

**Register** (`/register`)
- Fields and client validation (mirror the server):
  - Chama code: required.
  - First name, last name: required, max 100.
  - Email: required, valid, max 255. Trim spaces.
  - Phone: required. Accept `0712345678`, `712345678`, `254712345678`, `+254712345678` (spaces and dashes allowed), or any `+<country code>` number. Hint: "e.g. 0712 345 678".
  - Password: 8–72 characters, at least one letter and one digit. Show the rule live. Confirm field must match.
- `POST /auth/register` with the chama code header → then `POST /auth/login` with the same
  credentials → `/pending` ("Your application to join {chama} is waiting for approval. We've
  emailed you a link to verify your address.").
- `AUTH_USER_ALREADY_EXISTS` → "This email already has an account in this chama. Sign in instead." with link.

**Verify email** (`/verify-email`): on mount, `POST /auth/email/verify {token}` with `X-Chama-Code: chama`
(omit if no `chama` param). Success → "Email verified" + "Continue" (to `/` if signed in, else `/login?chama=`).
`AUTH_LINK_INVALID` → explain, and a "Send a new link" form (email → `/auth/email/verify/resend`).
Guard against React strict-mode double invocation: the token works once.

**Forgot password**: email (+ chama code) → always show "If an account exists for that email, a reset link is on its way."

**Reset password** (`/reset-password`): new password + confirm → `POST /auth/password/reset`.
Success → `/login?chama=…` with a success toast. `AUTH_LINK_INVALID` → link to forgot-password.

**Profile** (`/profile`): `GET /auth/me` (name, email, verified badge, phone, chama code, member
number and roles from `/members/me`). Actions: change password (current, new, confirm; on
`AUTH_CURRENT_PASSWORD_INCORRECT` mark the current-password field; store the returned tokens),
resend verification if `emailVerified` is false, sign out everywhere (confirm dialog).
Name/phone/email are read-only (no update endpoint).

### 7.2 Status screens

- **Pending** (`/pending`): chama name if known, "waiting for approval", applied date, a
  "Check again" button (re-fetch `/members/me`; poll every 60 s while visible), sign out.
- **Membership status** (`/membership-status`): REJECTED ("Your application was not approved"),
  SUSPENDED (show `suspensionReason`, "Contact your chama officials"), EXITED. Sign out.
- **Licence blocked** (`/licence-blocked`): state, plan, valid until, notes, server message.
  If the user has `CHAMA_SETTINGS_MANAGE`: "Contact the platform provider to renew".
  Keep "My profile" and sign-out reachable.

### 7.3 Member home (`/home`)

Data: `GET /dashboard/me` (`MemberDashboardView`). Sections are null when unlicensed; render only non-null ones.
Full field-by-field guide: [DASHBOARDS.md](DASHBOARDS.md#member-home-get-dashboardme).
- Greeting with name and member number; chama name.
- **Savings** card: `savings.totalMinor`, list of accounts (product name, account number, balance) → links to `/me/savings/:accountId`.
- **Contributions** card: current period and its status chip, arrears (red if > 0), advance, late periods. CTA "View contributions".
- **Loans** card: active loans, outstanding, arrears, next due date and amount; in-progress
  applications count; guarantee requests count with CTA "Respond" when > 0. CTA "Apply for a loan" if `LOAN_APPLY`.
- **Penalties** card: outstanding amount, CTA to `/me/penalties`.
- **Net position** headline: `netPositionMinor` (savings less what they owe).
- Contributions card also shows this year's total, compliance (% from `complianceRateBasisPoints`) and
  "Ranked {rank} of {rankOutOf}" when `rank` is not null.
- Loans card lists `loans.loans` with a progress bar from `repaidBasisPoints`.
- If `submissions.pending` > 0: "{n} payments awaiting confirmation".
- If `topInvestments` is not null: a small "How our investments are doing" list.
- A **"Report a payment"** button (`PAYMENT_SUBMIT`) → `/me/payments/new`; also on the penalties card
  (prefilled with the penalty) and on each active loan (prefilled with the loan).
- If the user has `CHAMA_VIEW`, show a link "Chama overview".

### 7.4 Chama overview (`/chama`)

Data: with `REPORT_VIEW_ALL`, `GET /dashboard` (`ChamaDashboardView`); otherwise, with `CHAMA_VIEW`,
`GET /dashboard/overview` (`ChamaOverviewView`). The member version has the same cards minus the
officials-only ones: no "Needs attention" panel, no top members, no "members owing most", no recent
ledger entries, and no investment figures without `INVESTMENT_VIEW`. Render from whichever view you
fetched and hide cards whose fields are absent or null. Null sections hidden.
Full field-by-field guide, including the top-members, top-investments and trends screens: [DASHBOARDS.md](DASHBOARDS.md).
- KPI row: active members (suspended, pending applications as subtext), cash total (cash on hand / bank / M-Pesa split), total savings, loans outstanding, investments market value.
- **Needs attention** panel from `pending`: each non-zero count is a link to the filtered list
  (applications → `/members?status=PENDING_APPROVAL`; loans awaiting approval → `/loans?status=PENDING_APPROVAL`;
  awaiting disbursement → `/loans?status=APPROVED`; expenses awaiting approval → `/expenses?status=PENDING_APPROVAL`;
  awaiting payment → `/expenses?status=APPROVED`; investments → `/investments?status=PROPOSED`).
- Contributions: current period, due date, collected vs expected (progress bar, `collectionRateBasisPoints`), members paid / late, total arrears and members in arrears.
- Loans: active, outstanding, arrears, loans in arrears, **PAR30** (% from bp; amber > 5%, red > 10%).
- Savings per product (bar or list). Investments: active, invested, market value, income.
- This month vs year to date: income, expenses, surplus (negative in red as deficit).
- Headline KPIs from `keyFigures`: total contributions, net worth, total assets (null figures hidden).
- **Top members** (`topMembers`): rank, name, contributed this year, compliance %; "See all" →
  `GET /dashboard/top-members` with a date-range picker.
- **Top investments** (`topInvestments`): name, return % (green profit / red loss), annualised %; each row
  links to `/investments/:id`; "See all" → `GET /dashboard/top-investments`.
- **Trends** chart from `trends` (contributions, income vs expenses per month); a 12/24-month toggle uses
  `GET /dashboard/trends?months=`.
- Penalties: owed, collected and waived this year, members owing most.
- Recent ledger entries (`recentEntries`): number, date, description, source type chip, amount; reversal entries marked.
- "As of {asOf}", "generated {time}". Refresh button.

### 7.5 Members (`/members`, `/members/:userId`)

**List**: `GET /members?status=&page=&size=`. Status tabs: Active, Pending, Suspended, Rejected, All.
Columns: member #, name, email (verified icon), phone, roles (chips), status, joined/applied date.
Client-side search by name/email/phone/number over loaded pages (there is no server search; load
all pages, at most 100 per page, and cache).

Row actions (show when allowed and the state fits; never on the user's own row):

| Action | Permission | When status is | Dialog |
|---|---|---|---|
| Approve | `MEMBER_APPROVE` | PENDING_APPROVAL | Confirm. On `LICENSE_MEMBER_LIMIT_REACHED` explain the licence limit. |
| Reject | `MEMBER_APPROVE` | PENDING_APPROVAL | Confirm |
| Suspend | `MEMBER_SUSPEND` | ACTIVE | Reason (required, ≤500) |
| Reactivate | `MEMBER_SUSPEND` | SUSPENDED | Confirm |
| Change roles | `MEMBER_ROLES_MANAGE` | ACTIVE | Checkboxes for TREASURER, SECRETARY, CHAIRPERSON, AUDITOR; MEMBER always on and disabled |

Errors to handle: `MEMBER_SELF_MANAGEMENT`, `MEMBER_LAST_CHAIRPERSON`, `MEMBER_INVALID_STATE` (refetch).

**Invite** button (`MEMBER_INVITE`): email → `POST /members/invitations`; toast with the server message.
Also show the chama code with a copy button and a shareable join link `{origin}/join?chama={code}`.

**Member detail** (`/members/:userId`): there is no single-member endpoint; take the row from the
cached list. Tabs, each shown only with its permission:
- Accounts: `GET /member-accounts?memberUserId=` (`MEMBER_ACCOUNTS_VIEW_ALL`)
- Contributions: `GET /contributions?memberUserId=` (`CONTRIBUTION_VIEW_ALL`) and their row from `/contributions/standings`
- Loans: `GET /loans?memberUserId=` (`LOAN_VIEW_ALL`)
- Penalties: `GET /penalties?memberUserId=` (`PENALTY_VIEW_ALL`)
- Statement: `GET /reports/members/{userId}/statement` (`REPORT_VIEW_ALL`)
Quick actions: Record contribution (prefilled member), Fine, Open account.

### 7.6 Contributions

**Payments** (`/contributions`): `GET /contributions` with filters member, from, to, status. Columns:
date paid, member (#, name), amount, method + reference, allocations (product: amount), status
(REVERSED struck through with reason tooltip), recorded at. Row click → detail drawer
(`GET /contributions/{id}`), with **Reverse** (`CONTRIBUTION_REVERSE`, status RECORDED, not own payment) → reason dialog.

**Record a contribution** (`/contributions/new`, `CONTRIBUTION_RECORD`):
- Member: searchable picker of seated members (ACTIVE + SUSPENDED), **excluding the current user**
  (they cannot record their own). Show their current arrears from `/contributions/standings` as a hint.
- Amount (KES), Method (Cash / Bank / M-Pesa), Reference (required and labelled "M-Pesa code" or
  "Bank slip number" when the method needs it; uppercase M-Pesa codes), Date paid (default today, max today), Notes (≤300).
- "Split across products" toggle (advanced): list active savings products the member has accounts
  in, with an amount each; must add up to the total (show the remaining amount live). Off = automatic
  allocation (oldest dues first by product priority, the rest to Deposits).
- On `CONTRIBUTION_DUPLICATE_REFERENCE`: error on the reference field. After success: toast,
  show the allocation result, offer "Record another".

**Periods** (`/contributions/periods`): table of `PeriodSummaryView`: period key, dates, due date,
due chip, expected, collected (progress), members paid / members, late count.
**Period detail**: summary + members table (member, due, paid, status chip PAID/PARTIALLY_PAID/UNPAID, late flag).

**Standings** (`/contributions/standings`): toggle "Only in arrears". Columns: member, total paid,
due to date, arrears, advance, balance (signed, coloured), late periods; expandable per-product breakdown.

**My contributions** (`/me/contributions`): `GET /contributions/me`: standing summary, periods
list (key, due date, due, paid, status, late), recent payments.

### 7.7 Savings products and member accounts

**Savings products** (`/settings/savings-products`): list `SavingsProductView` (name, code, kind,
ledger code, contribution per period, priority, mandatory, flags, members, total balance, active).
`PRODUCTS_MANAGE` can:
- Create: code (≤10), name (≤60), kind, contribution per period (≥0), allocation priority (1–99,
  default 10; Deposits is 50, lower is paid first), mandatory, optional flags (default from kind:
  DEPOSITS refundable+loan-backing+dividend; SHARE_CAPITAL dividend; WELFARE none; CUSTOM refundable),
  optional ledger account code. Explain: "A mandatory product opens an account for every member and
  is owed from the next period."
- Edit (terms for future periods). For the default product the contribution field is disabled with
  "Set in chama settings".
- Activate/deactivate (not the default product).

**Member accounts** (`/member-accounts`): filters member, product, status. Columns: account number,
member, product, status, opened, balance. Detail: statement with date range (`from`/`to`),
opening/closing balance, lines (date, entry #, description, reference, debit, credit, running
balance). `MEMBER_ACCOUNTS_MANAGE`: Open account (member + non-mandatory product), Close
(zero balance only, reason), Reopen.

**My savings** (`/me/savings`): `GET /member-accounts/me`: total and account cards. Account page:
`GET /member-accounts/me/{id}/statement?from&to`.

### 7.8 Penalties

**List** (`/penalties`): filters member, status. Columns: assessed on, member, kind (Late
contribution / Fine), description, amount, paid, waived, outstanding, status. Header action
"Run late-penalty check" (`PENALTY_RECORD`, `POST /penalties/assess`) and "Fine a member".
**Detail**: penalty + payments. Actions while OUTSTANDING and not the user's own penalty:
Record payment (`PENALTY_RECORD`; amount ≤ outstanding, method, reference, date), Waive
(`PENALTY_WAIVE`; reason; confirm "waives KES X still outstanding").
**Fine**: member picker (excluding self), amount, reason.
**My penalties**: `GET /penalties/me`: total outstanding and list. Each outstanding penalty has
"I've paid this" (`PAYMENT_SUBMIT`) → the payment report form prefilled with that penalty.

### 7.8a Reported payments

Members don't record payments; they **report** them and the treasurer confirms. Nothing changes on
their balances until a report is approved, so word the UI as "awaiting confirmation", never "paid".

**Report a payment** (`/me/payments/new`, `PAYMENT_SUBMIT`): `POST /payment-submissions`.
- What for: Contribution / Penalty / Loan repayment (show only the kinds whose feature is licensed).
  Penalty → picker of `MemberDashboardView.penalties.outstanding` (show outstanding amount);
  Loan → picker of the member's `ACTIVE` loans (show outstanding). Cap the amount at the outstanding.
- Amount, Method (M-Pesa / Bank / Cash), Reference (required unless Cash; "M-Pesa code" uppercased),
  Date paid (default today, max today), Notes (≤300).
- Errors: `SUBMISSION_DUPLICATE_REFERENCE` on the reference field; `SUBMISSION_TARGET_INVALID` → show the
  message (it says what is outstanding). On success toast the server message and go to `/me/payments`.

**My reported payments** (`/me/payments`): `GET /payment-submissions/me`. Rows: date paid, what for
(`kind` + `targetDescription`), amount, method + reference, status chip (Pending amber, Approved green,
Rejected red with `reviewNote`, Cancelled grey). Pending rows have **Withdraw** (`POST …/{id}/cancel`, confirm).
Rejected rows offer "Report again" (prefilled copy).

**Payments to confirm** (`/payment-submissions`, any record permission): `GET /payment-submissions`
(defaults to every status; use a Pending tab with `status=PENDING`). Kind filter from
`GET /payment-submissions/reviewable-kinds`. Columns: submitted, member (#, name), what for, amount,
method + reference, date paid, notes. Actions on PENDING rows not submitted by the current user:
**Confirm** (optional note) and **Reject** (reason required, which the member is emailed). Explain
on Confirm that it records the payment in the books under the user's name. A user's own reports
show "Another official must confirm this". Errors from the owning module (e.g. the penalty was paid
down meanwhile) → show the message and refetch.

### 7.9 Loans

**Apply** (`/me/loans/apply`, `LOAN_APPLY`):
1. Product picker from `GET /loan-products` (active only; default product preselected).
2. `GET /loans/eligibility?productId=` → panel: savings counted, multiplier, other loans,
   **max loan**, interest rate, max term, membership months vs. minimum, guarantor threshold and
   number needed, approval quorum. If `!eligible` list `reasons` and disable the form.
3. Form: principal (≤ max loan), term months (1 … product `maxTermMonths`), purpose (≤300).
   Live preview: interest = principal × rate (flat), total repayable, monthly instalment ≈ total ÷ term
   (label "estimate"; the server schedule is authoritative).
4. Guarantors: required when principal > `guarantorThresholdMinor`; pick exactly
   `guarantorsRequired` (or more, max 10) from `GET /loans/guarantor-candidates` (active members,
   yourself excluded; plain members cannot call `GET /members`).
5. Submit → loan detail. `LOAN_NOT_ELIGIBLE` → show the message (it lists reasons).

**My loans** (`/me/loans`): `GET /loans/me`: totals; each loan card with status chip, progress
(repaid / total due), next due, and expandable detail: guarantors with status, approvals
(n of required), schedule table (instalment #, due date, amount, paid, status PAID/PARTLY_PAID/DUE/OVERDUE),
repayments. Borrower actions: **Cancel** (any status before ACTIVE; confirm), **Add guarantor**
(status PENDING_GUARANTORS; picker excludes self and existing guarantors).

**Guarantee requests** (`/me/guarantees`): `GET /loans/guarantees`: borrower, amount, product,
purpose, term, my status. For REQUESTED: Accept / Decline (confirm; explain that accepting means
standing surety for the loan).

**Loan book** (`/loans`, `LOAN_VIEW_ALL`): status tabs (Pending guarantors, Pending approval,
Approved, Active, Repaid, Rejected, Cancelled, All) and member filter. Columns: applied, borrower,
product, principal, term, status, approvals (n/m), outstanding, arrears (red), next due.

**Loan detail** (`/loans/:id`): all of the loan detail plus official actions:

| Action | Permission | When | Hidden/disabled when |
|---|---|---|---|
| Approve (note optional) | `LOAN_APPROVE` | PENDING_APPROVAL | user is borrower, a guarantor, or already in `approvals` |
| Reject (reason) | `LOAN_APPROVE` | PENDING_GUARANTORS, PENDING_APPROVAL, APPROVED | user is borrower |
| Disburse (method, reference, date) | `LOAN_DISBURSE` | APPROVED | user is borrower |
| Record repayment (amount ≤ outstanding, method, reference, date) | `LOAN_REPAYMENT_RECORD` | ACTIVE | user is borrower |

After approve, toast the server message ("Approval recorded (1 of 2)" / "Loan approved").
Disburse: show the source account balance if known (`GET /ledger/accounts` with `LEDGER_VIEW`, or
the chama dashboard cash section); on `LOAN_INSUFFICIENT_FUNDS` show the message.

**Loan products** (`/settings/loan-products`): list; `PRODUCTS_MANAGE` can create (code, name,
interest %, max term, max multiplier, min membership months, guarantor threshold, guarantors
required), edit (default product: name only, other fields disabled with "Set in chama settings"),
activate/deactivate (not default).

### 7.10 Expenses

**List** (`/expenses`): summary strip from `GET /expenses/summary` (this month by default; range
picker): paid total and count, awaiting payment, per-account totals. Table filters status,
investment, incurred from/to. Columns: incurred on, payee, description, account, investment,
amount, status, approvals (n/m), requested by.

**New expense** (`EXPENSE_CREATE`): amount, payee (≤120), description (≤300), expense account
(active EXPENSE accounts from `GET /ledger/accounts` if the user has `LEDGER_VIEW`; otherwise leave
it out to default to 5000 Operating expenses), investment (optional; ACTIVE investments, if the
INVESTMENTS feature is on), incurred on (max today).

**Detail** actions:

| Action | Permission | When | Not shown when |
|---|---|---|---|
| Approve (note) | `EXPENSE_APPROVE` | PENDING_APPROVAL | user is `requestedBy` or already approved |
| Reject (reason) | `EXPENSE_APPROVE` | PENDING_APPROVAL, APPROVED | user is `requestedBy` |
| Cancel | `EXPENSE_CREATE` | PENDING_APPROVAL, APPROVED | user is not `requestedBy` |
| Pay (method, reference, date) | `EXPENSE_CREATE` | APPROVED | — |

Show approval progress ("1 of 2 approvals") and the approvers with notes.

### 7.11 Investments

**List** (`/investments`): filters status, type. Columns: name, type, institution, amount,
book value, market value, return %, status, maturity.
**Portfolio** (`/investments/portfolio`): active count, book vs market value, invested, income,
expenses, realised and unrealised gains, total return and return % (from bp), allocation by type
(donut), "Maturing in the next 30 days" list.
**Propose** (`INVESTMENT_MANAGE`): name, type, institution, description, amount, expected yearly
return % (optional), maturity date (optional, future).
**Detail**: header figures; **performance** card when funded (invested, income, expenses, net
income, gain (realised/unrealised), total return, return %, annualised % if not null, days held,
profitable badge); approvals; transactions (kind, date, amount, method, reference, note); valuations.
Actions:

| Action | Permission | When | Not shown when |
|---|---|---|---|
| Approve / Reject | `INVESTMENT_APPROVE` | PROPOSED (reject also APPROVED) | user is `proposedBy`; approve also if already approved |
| Cancel | `INVESTMENT_MANAGE` | PROPOSED, APPROVED | user is not `proposedBy` |
| Fund (method, reference, date) | `INVESTMENT_MANAGE` | APPROVED | — (amount fixed = approved amount; show it) |
| Deposit / Record income / Withdraw / Record valuation / Close | `INVESTMENT_MANAGE` | ACTIVE | — |

Income method includes "Reinvested". Withdrawal must be less than book value (hint shows it).
Close: proceeds (≥0), method, reference, date, note; preview gain/loss = proceeds − book value.
Expenses linked to the investment: link to `/expenses?investmentId=…`.

### 7.12 Ledger

**Chart of accounts** (`/ledger/accounts`): grouped by type (Assets, Liabilities, Equity, Income,
Expenses), columns code, name, member-ledger flag, active, balance. `LEDGER_ACCOUNTS_MANAGE`:
add account (code ≤10, name ≤100, type), activate/deactivate (not for system accounts, `systemKey != null`).
**Account statement** (`/ledger/accounts/:id`): date range, and a member filter for member-ledger
accounts; opening/closing balance and lines with running balance; entry # links to the journal.
**Journal** (`/ledger/journal`): filters from/to/source type; columns #, date, description,
reference, source type chip, total, reversed/reversal markers. **Entry detail**: lines table
(account, member, memo, debit, credit), links to the reversal/original, and **Reverse**
(`LEDGER_POST`, reason) for MANUAL entries. For entries created by a business record (contribution,
loan, expense…), point the user to that record instead.
**New manual entry** (`LEDGER_POST`): date (max today), description (≤500), reference; line editor
(2–200 lines): account (picker of active accounts), member (required and shown only when the
account is a member ledger), debit **or** credit, memo. Footer shows total debits, total credits
and the difference; submit disabled until they match and are > 0.
**Trial balance** (`/ledger/trial-balance`): as-of date; rows code, name, type, debit, credit; totals; "Balanced" badge.

### 7.13 Reports (`/reports/*`, `REPORT_VIEW_ALL` + REPORTS)

A report index page linking to each. Each report has its parameters at the top, a print-friendly
layout (`@media print`), and "Download CSV" generated client-side.

| Report | Params | Layout |
|---|---|---|
| Financial position | as of | Assets / Liabilities / Equity sections with lines and totals; "Liabilities + equity"; balanced badge |
| Income statement | from, to | Income and Expense sections; surplus/deficit |
| Cash book | account (all, 1000, 1010, 1020), from, to | Per account: opening, receipts, payments, closing, lines with running balance; combined totals |
| Contributions | from, to | Matrix: rows = members, columns = periods (key), cells "paid / due" coloured by status, late marker; row totals (paid, arrears, advance); column totals (collected / expected) |
| Loan portfolio | — | Summary (active, outstanding, arrears, PAR30, pipeline); ageing buckets table/bar; loans table with bucket, days in arrears |
| Savings register | — | Matrix: members × products with balances; column and grand totals |
| Member statement | member, from, to | Summary (savings, loans outstanding, penalties, net position), contribution standing, each savings account's movements, loans, penalties account movements |

**My statement** (`/me/statement`) uses `GET /reports/members/me/statement` with the same layout.

### 7.14 Audit log (`/audit-log`)

Paged table: time, actor (resolve `actorUserId` to a name from the member list; "System" when
null), action (humanised: `LOAN_APPROVED` → "Loan approved"), target type + id, IP. Expand a row to
show `details` as key/value pairs (before/after values for settings changes). Filter by action
(dropdown from `KNOWN_AUDIT_ACTIONS`, exact match).

### 7.15 Settings

**Chama profile & rules** (`/settings/chama`): `GET /chama`. Read-only: name, code (copy button),
description, currency, status, created. Rules form (`CHAMA_SETTINGS_MANAGE`, otherwise read-only),
grouped:
- Contributions: amount, frequency (Monthly/Weekly), due day (1–28 day of month, or weekday picker 1=Mon…7=Sun), grace days (0–31), late penalty % (0–100).
- Loans (default loan product): max multiple of savings (1–10), flat interest % (0–100), minimum membership months (0–60), guarantor threshold amount, guarantors required (0–5).
- Approvals: quorum (1–10). Warn if quorum exceeds the number of members holding approve permissions.
`PUT /chama/settings` sends all fields. Explain "Changes apply to future contribution periods and new loan applications."

**Licence** (`/settings/licence`): plan, state chip, valid from/until, days remaining, member limit
vs active members (from `GET /dashboard` members section if allowed), features as chips (included/not), notes.

**Roles & permissions** (`/settings/roles`): `GET /roles` as a matrix: rows = permissions grouped
by area (with the descriptions from §2.3), columns = roles. `ROLE_PERMISSIONS_MANAGE` can toggle
cells; save per role (`PUT /roles/{role}/permissions` with the full set). Lock the two chairperson
permissions (disabled, tooltip). Confirm before saving: "Changes apply immediately to everyone with this role."
After saving, re-fetch `/members/me`.

**Import opening balances** (`/settings/import`, `CHAMA_SETTINGS_MANAGE`): a wizard.
1. `GET /imports/opening-balances`: if `data` is not null, show the completed import (as-of,
   file, by, summary, warnings) and stop; imports happen once.
2. Explain: create savings products first; download the template (`GET …/template` as Blob with
   bearer, save as `opening-balances-template.xlsx`).
3. Upload `.xlsx` (≤5 MB) + cut-over date (max today) → **Preview** (`multipart/form-data`: `file`, `asOf`).
4. Show summary (new/existing members, savings, loans, penalties, cash/bank/M-Pesa, investments,
   retained surplus) and problems table grouped by sheet (severity, sheet, row, column, message);
   errors block, warnings don't.
5. If `importable`, "Import" with a strong confirmation ("This can be done only once") → `POST`.
   Success: summary + note that new members were emailed a set-password link.

### 7.16 Platform console

**Chamas** (`/platform`): paged table: name, code, currency, plan, state chip, valid until, days
remaining, max members, features count, created. Highlight expiring (≤14 days) and expired/suspended.

**Onboard** (`/platform/chamas/new`): Chama (name ≤120, code 3–30 letters/digits/single hyphens,
description ≤1000, currency default KES, optional rules with STANDARD_SETTINGS prefilled);
Administrator (first/last name, email, phone, initial password); optional Licence (plan, valid until,
max members, features; default: 30-day trial). On success show the code and admin email, and
"The administrator has been emailed a verification link". `CHAMA_CODE_TAKEN` → field error on code.

**Chama detail** (`/platform/chamas/:id`): chama info and settings (read-only); licence panel with
actions: Renew (new valid-until date; send as an ISO instant at the end of that day in Nairobi,
e.g. 31 Dec 2027 → `2027-12-31T20:59:59Z`), Change plan (warn that member limit/features reset to
plan defaults unless edited), Edit member limit, Edit features (checkboxes), Suspend (notes
required by UX) / Reactivate (`status: ACTIVE`), Notes. Each sends only changed fields to
`PUT /platform/chamas/{id}/license`.

---

## 8. Cross-cutting UX rules

### 8.1 Self-action and maker-checker rules

The server enforces these; the UI should hide or disable the action with a tooltip explaining why.

| Rule | Error if attempted |
|---|---|
| No one approves, rejects, suspends, reactivates or changes roles of their own membership | `MEMBER_SELF_MANAGEMENT` |
| A chama always keeps ≥1 active chairperson | `MEMBER_LAST_CHAIRPERSON` |
| Chairperson keeps `MEMBER_ROLES_MANAGE` + `ROLE_PERMISSIONS_MANAGE` | `ROLE_PERMISSIONS_LOCKOUT` |
| Officials don't record or reverse their own contributions | `CONTRIBUTION_SELF_RECORDING` |
| Officials don't fine themselves, waive or collect their own penalties | `PENALTY_SELF_HANDLING` |
| Officials don't approve, reject, disburse or record repayments on their own loans | `LOAN_SELF_HANDLING` |
| A loan's guarantor cannot approve it | `LOAN_GUARANTOR_CANNOT_APPROVE` |
| Each official approves a loan/expense/investment once | `*_ALREADY_APPROVED` |
| Whoever recorded an expense / proposed an investment cannot approve or reject it | `EXPENSE_SELF_APPROVAL`, `INVESTMENT_SELF_APPROVAL` |
| Only the recorder cancels an expense; only the proposer cancels an investment; only the borrower cancels a loan or adds guarantors | `EXPENSE_NOT_REQUESTER`, `INVESTMENT_NOT_PROPOSER`, `LOAN_NOT_BORROWER` |

Show quorum progress on every approvable record: "Approvals: 1 of 2" with approver names.

### 8.2 Money-movement forms

Shared component for "how was the money paid": Method (Cash / Bank / M-Pesa), Reference (required
for Bank and M-Pesa; label changes with the method; ≤100), Date (default today in Nairobi, max today).
References must be unique per kind of record: show `*_DUPLICATE_REFERENCE` on the reference field.
Money going out (disburse, pay expense, fund/deposit investment) fails with `*_INSUFFICIENT_FUNDS`
when cash/bank/M-Pesa holds too little: show the message, which includes the available amount.

### 8.3 Confirmations

Ask for confirmation (with a summary of amounts) before: reversing a contribution, waiving a
penalty, approving/rejecting anything, disbursing a loan, paying an expense, funding/closing an
investment, reversing a journal entry, suspending a member, changing role permissions, saving chama
settings, importing opening balances. Reasons are required where the API requires them.

### 8.4 Stale data and conflicts

On `409` with `*_WRONG_STATUS`, `*_ALREADY_*`, `CONCURRENT_MODIFICATION` or `DATA_CONFLICT`:
show the message, refetch the record, and re-render its actions (someone else acted first).

### 8.5 Status chips (suggested colours)

| Status | Colour |
|---|---|
| ACTIVE (member/loan/investment/account), PAID, APPROVED, RECORDED, REPAID | green |
| PENDING_*, PROPOSED, PARTIALLY_PAID / PARTLY_PAID, DUE, OUTSTANDING, REQUESTED | amber |
| OVERDUE, UNPAID (late), REJECTED, SUSPENDED, EXPIRED, DECLINED | red |
| CANCELLED, REVERSED, WAIVED, CLOSED, WITHDRAWN, EXITED, ARCHIVED | grey |

### 8.6 General

- Mobile first: most members use phones. Tables collapse to cards below 640 px. Touch targets ≥ 44 px.
- Accessible: labelled inputs, focus management in dialogs, colour never the only signal (add icon/text), WCAG AA contrast.
- English copy, but keep all strings in one place so Swahili can be added later.
- Don't show raw UUIDs; show names, member numbers, account numbers, entry numbers (`#57`).
- Disable submit buttons while a request is in flight (prevents duplicate payments).
- Health: if `/actuator/health` fails or requests fail with network errors, show a top banner.

---

## 9. Data refresh after changes

After a successful mutation, invalidate these cached queries (TanStack Query keys suggested as
`[area, ...]`). When unsure, invalidate the area plus both dashboards.

| Mutation | Invalidate |
|---|---|
| Approve/reject/suspend/reactivate member, change roles, invite | `members`, `dashboard`, `me.membership` (if self affected) |
| Role permissions | `roles`, `me.membership` |
| Chama settings | `chama`, `loanProducts`, `savingsProducts`, `contributions.periods` |
| Record / reverse contribution | `contributions.*`, `memberAccounts`, `ledger`, `dashboard`, `dashboard.me`, `penalties` |
| Savings product create/update/(de)activate | `savingsProducts`, `memberAccounts`, `ledger.accounts` |
| Member account open/close/reopen | `memberAccounts` |
| Fine, penalty payment, waive, assess | `penalties`, `ledger`, `dashboard`, `dashboard.me` |
| Loan apply/cancel/guarantee/add guarantor | `loans`, `loans.me`, `loans.guarantees`, `dashboard`, `dashboard.me` |
| Loan approve/reject/disburse/repayment | `loans`, `loans.detail(id)`, `ledger`, `dashboard`, `reports` |
| Loan product changes | `loanProducts`, `loans.eligibility` |
| Expense create/approve/reject/cancel/pay | `expenses`, `expenses.summary`, `ledger`, `dashboard`, `investments.detail(id)` if linked |
| Investment actions | `investments`, `investments.portfolio`, `investments.detail(id)`, `ledger`, `dashboard` |
| Ledger account / manual entry / reversal | `ledger.*`, `reports`, `dashboard` |
| Opening-balance import | everything (reload the app data) |
| Licence update (platform) | `platform.chamas`, `platform.chama(id)` |

Refetch dashboards on window focus. No server push exists; poll the work-queue counts every 2–5 minutes at most.

---

## 10. Formatting reference

```ts
// src/lib/format.ts

/** Fraction digits of a currency (KES 2, UGX 0). */
export const fractionDigits = (currency: string) =>
  new Intl.NumberFormat('en-KE', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2;

/** 150050, "KES" -> "KES 1,500.50" */
export const formatMoney = (minor: number, currency: string) =>
  new Intl.NumberFormat('en-KE', { style: 'currency', currency }).format(minor / 10 ** fractionDigits(currency));

/** "1,500.5" -> 150050. String math: no floating-point error. Throws on bad input. */
export function parseMoney(input: string, currency: string): number {
  const digits = fractionDigits(currency);
  const clean = input.replace(/[,\s]/g, '').replace(/^KES/i, '');
  if (!/^\d+(\.\d+)?$/.test(clean)) throw new Error('Enter an amount like 1500 or 1,500.50');
  const [whole, frac = ''] = clean.split('.');
  if (frac.length > digits) throw new Error(`At most ${digits} decimal places`);
  return Number(whole) * 10 ** digits + Number(frac.padEnd(digits, '0') || '0');
}

/** 1250 -> "12.5%" */
export const formatBp = (bp: number) => `${(bp / 100).toLocaleString('en-KE', { maximumFractionDigits: 2 })}%`;
/** "12.5" -> 1250 */
export const parsePercentToBp = (input: string) => Math.round(Number(input) * 100);

/** Today's date in Nairobi as "YYYY-MM-DD" (for defaults and max on date pickers). */
export const todayNairobi = () =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Nairobi' }).format(new Date());

/** "2026-11-08" -> "8 Nov 2026" without timezone shifts. */
export const formatDate = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-KE', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
};

/** Instant -> local date-time. */
export const formatInstant = (iso: string) =>
  new Date(iso).toLocaleString('en-KE', { dateStyle: 'medium', timeStyle: 'short' });

/** "2026-11" -> "Nov 2026"; "2026-W45" -> "Week 45, 2026" */
export const formatPeriodKey = (key: string) => {
  const week = key.match(/^(\d{4})-W(\d{2})$/);
  if (week) return `Week ${Number(week[2])}, ${week[1]}`;
  const [y, m] = key.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString('en-KE', { month: 'short', year: 'numeric', timeZone: 'UTC' });
};

/** "LOAN_APPROVAL_GIVEN" -> "Loan approval given" */
export const humanize = (code: string) => code.charAt(0) + code.slice(1).toLowerCase().replace(/_/g, ' ');
```

Labels worth hard-coding rather than humanising:

| Value | Label |
|---|---|
| `MPESA` | M-Pesa |
| `MOBILE_MONEY` | Mobile money (M-Pesa) |
| `MONEY_MARKET_FUND` | Money market fund |
| `SACCO_SHARES` | SACCO shares |
| `LATE_CONTRIBUTION` | Late contribution |
| `PENDING_GUARANTORS` | Waiting for guarantors |
| `PENDING_APPROVAL` | Waiting for approval |
| `DAYS_1_30` … `OVER_90` | use `BucketTotal.label` from the API |
| `REINVESTED` | Reinvested |

---

## 11. Running the backend locally

```bash
# 1. Infrastructure: PostgreSQL (15432), RabbitMQ (15673), Redis (16379), Mailpit (SMTP 11025, web 18025)
docker compose -f infrastructure/docker/docker-compose.yml up -d

# 2. API on http://localhost:8080, with a platform administrator created at start-up
cd backend
PLATFORM_ADMIN_EMAIL=admin@platform.local PLATFORM_ADMIN_PASSWORD='Admin12345' \
FRONTEND_BASE_URL=http://localhost:3000 ./mvnw spring-boot:run
```

The repository's top-level README has the same steps plus a curl walkthrough of the core flow.

Then:
1. Sign in at `/admin/login` as the platform admin and onboard a chama (or `POST /api/v1/platform/chamas`).
2. Open Mailpit at <http://localhost:18025> to read every email the backend sends (verification,
   invitations, password resets, receipts) and click the links into your dev server.
3. Sign in at `/login` with the chama code and the chama administrator's credentials.
4. Register a few members with the chama code, approve them, assign roles (you need at least two
   officials with approve permissions to complete loans/expenses/investments with the default quorum of 2).

Swagger UI: <http://localhost:8080/swagger-ui.html>. OpenAPI JSON: <http://localhost:8080/v3/api-docs>
(optionally generate types with `openapi-typescript` and diff them against `api-types.ts`).

---

## 12. Known backend gaps and workarounds

| Gap | Workaround in the frontend |
|---|---|
| No CORS | Same-origin proxy (§3.1) |
| No `GET /members/{id}` and no member search | Load all pages of `GET /members` (≤100 per page), cache, search client-side |
| No profile update (name, phone, email) | Read-only profile |
| No member exit / final settlement (status `EXITED` reserved) | Don't offer "remove member"; suspension is the only off-boarding |
| No M-Pesa payment initiation (STK push) or SMS, although licence features exist | Payments are recorded manually with the M-Pesa code; hide MPESA/SMS features |
| No dividend / profit distribution endpoint | Don't build one |
| No notifications feed or push | Derive to-dos from dashboard `pending` and `loans.guaranteeRequests`; poll |
| No server-side exports (PDF/CSV) | CSV and print stylesheet client-side |
| No chama self-signup; only platform admins onboard chamas | Public site links to "Contact us to register your chama" |
| Audit log shows actor id only; filter is exact action match; no date filter | Resolve names from members list; action dropdown |
| No endpoint to list sent invitations | Show the success toast only |
| No platform-admin management (create admins, archive chamas) | Not in the console |
| Lists `/contributions/periods`, `/contributions/standings`, `/member-accounts` are not paged | Virtualise long tables |

---

## 13. Recommended stack and project layout

Unless the project dictates otherwise:

- **React 18 + TypeScript (strict) + Vite** (dev server on port 3000 with `/api` proxy).
- **React Router** for routing and guards; **TanStack Query** for server state and the
  invalidation map in §9; **react-hook-form + zod** for forms (mirror the server rules; still map
  `error.fields`).
- A component library with good tables and dialogs (e.g. MUI, Mantine, or shadcn/ui + TanStack Table);
  **Recharts** for the few charts (collection progress, ageing buckets, portfolio by type).
- Testing: Vitest + Testing Library for components; Playwright for the main flows against the local backend.

```
src/
  api/
    types.ts            ← copy of docs/frontend/api-types.ts
    client.ts           ← §4.6
    endpoints/          ← one file per area: auth.ts, members.ts, contributions.ts, loans.ts, …
  auth/                 session store, guards, AppContext (§5)
  lib/format.ts         §10
  components/           MoneyInput, MoneyText, DateInput (max today), PaymentMethodFields,
                        MemberPicker, StatusChip, ApprovalProgress, ConfirmDialog, DataTable,
                        EmptyState, ErrorState, PermissionGate, FeatureGate
  features/
    auth/ home/ chama-overview/ members/ contributions/ savings/ penalties/ loans/
    expenses/ investments/ ledger/ reports/ audit/ settings/ import/ platform/
  routes.tsx
```

---

## 14. Build order

1. **Foundation**: project setup, proxy, API client with refresh lock, session store, start-up
   flow (§5), layout with permission/feature-aware navigation, error/toast handling, formatting helpers.
2. **Auth & onboarding**: login, admin login, register, join, verify email, forgot/reset password,
   pending/status/licence screens, profile.
3. **Member self-service**: home, my savings + statements, my contributions, my penalties, my loans,
   apply for a loan, guarantee requests, my statement.
4. **Membership administration**: members list and actions, invite, member detail, roles & permissions.
5. **Collections**: record contribution, payments, periods, standings, penalties admin, savings products, member accounts.
6. **Lending**: loan book, loan detail with approve/reject/disburse/repay, loan products.
7. **Spending & investing**: expenses, investments, portfolio.
8. **Books & reports**: chama overview dashboard, ledger screens, all reports, audit log.
9. **Settings & migration**: chama settings, licence view, opening-balance import wizard.
10. **Platform console**: chamas list, onboarding, licence management.
11. **Polish**: responsive pass, accessibility, empty/error states, print styles, CSV export, e2e tests.

---

## 15. Acceptance checklist

Session and access
- [ ] Member login sends `X-Chama-Code`; admin login sends none; last chama code is remembered.
- [ ] Public auth calls never carry `Authorization`.
- [ ] Two simultaneous 401s and two open tabs cause exactly one `/auth/refresh` call (verify in the network tab), and the user stays signed in.
- [ ] Refresh and logout send the stored chama code.
- [ ] Password change stores the returned tokens; logout-all and logout clear every tab.
- [ ] Locked account shows a countdown from `retryAfterSeconds`.
- [ ] Applicant sees the pending screen; suspended member sees the reason; expired licence shows the blocked screen.
- [ ] Every nav item and action is gated by permission and licence feature; changing a role's permissions is reflected after re-fetching `/members/me`.

Data handling
- [ ] All money is integer minor units end to end; `parseMoney("0.1")` + `parseMoney("0.2")` = 30.
- [ ] Business dates are never shifted by timezone; date pickers cap at today in Nairobi.
- [ ] `error.fields` show next to the right inputs, including nested paths like `lines[1].accountCode`.
- [ ] Every successful mutation shows the server `message` and refreshes affected data (§9).

Business rules visible in the UI
- [ ] Self-actions in §8.1 are hidden or disabled with an explanation.
- [ ] Approval progress "n of m" shown on loans, expenses and investments.
- [ ] Reference is required for Bank/M-Pesa on every money form; duplicate reference errors land on the field.
- [ ] Loan apply shows eligibility and reasons; guarantor picker enforces the required count and excludes self.
- [ ] Manual journal entry cannot be submitted unbalanced; member field appears only for member-ledger accounts.
- [ ] Opening-balance import can only be done once and shows row-level problems.

Email links
- [ ] `/verify-email`, `/reset-password`, `/join` and `/` work when opened from Mailpit emails.
