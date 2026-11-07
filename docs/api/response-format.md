# API response format

Every endpoint returns the same JSON envelope, for successes and errors alike. A client
checks one field, `success`, and then reads either `data` or `error`.

Swagger UI (`http://localhost:8080/swagger-ui.html`) shows the exact `data` shape of each endpoint.

Every chama is a tenant: a user belongs to exactly one chama. Public auth endpoints (register, login,
refresh, logout, email verification, password reset) must send the chama's code in the `X-Chama-Code`
header, e.g. `X-Chama-Code: mokica`; logged-in requests take the chama from the access token. Emailed
links carry it as `?chama=<code>&token=...`. Platform administrators send no header.

## Shape

| Field       | Present          | Meaning                                                                    |
|-------------|------------------|----------------------------------------------------------------------------|
| `success`   | always           | `true` for 2xx responses, `false` otherwise                                |
| `message`   | always           | Human-readable outcome. **Safe to show to the user** (toast, alert, banner) |
| `data`      | always           | The result. `null` when there is nothing to return, and on every error     |
| `meta`      | paged lists only | Paging details, see below                                                  |
| `error`     | errors only      | Machine-readable details, see below                                        |
| `timestamp` | always           | Server time, ISO-8601 UTC                                                  |

HTTP status codes still carry meaning (201 created, 400 invalid input, 401, 403, 404, 409, 422, 429, 500),
and `error.status` repeats the code in the body.

### Success

```json
{
  "success": true,
  "message": "Chama created",
  "data": { "id": "f01c2282-45b3-4399-9e3f-61687f436cbc", "name": "Umoja Chama", "currency": "KES" },
  "timestamp": "2026-11-06T09:00:00Z"
}
```

Endpoints with nothing to return (logout, verify email, reset password) still answer `200` with a message:

```json
{ "success": true, "message": "Logged out", "data": null, "timestamp": "2026-11-06T09:00:00Z" }
```

### Paged list

`data` is the array of items and `meta` holds the paging details. Pages are zero-based: `?page=0&size=50`.

```json
{
  "success": true,
  "message": "OK",
  "data": [ { "membershipId": "…", "memberNumber": 1, "status": "ACTIVE" } ],
  "meta": { "page": 0, "size": 50, "totalElements": 1, "totalPages": 1, "hasNext": false, "hasPrevious": false },
  "timestamp": "2026-11-06T09:00:00Z"
}
```

### Error

```json
{
  "success": false,
  "message": "One or more fields are invalid",
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "status": 400,
    "path": "/api/v1/auth/register",
    "fields": [
      { "field": "email", "message": "must be a well-formed email address" },
      { "field": "settings.loanMaxMultiplier", "message": "must be greater than 0" }
    ]
  },
  "timestamp": "2026-11-06T09:00:00Z"
}
```

| `error` field       | Meaning                                                                                       |
|---------------------|-----------------------------------------------------------------------------------------------|
| `code`              | Stable reason to branch on, e.g. `AUTH_ACCOUNT_LOCKED`. Its wording never changes, unlike `message` |
| `status`            | The HTTP status                                                                               |
| `path`              | The request path that failed                                                                  |
| `fields`            | One entry per invalid input. **Always an array**, empty when the error is not about specific fields. `field` matches the JSON property path the client sent (`settings.loanMaxMultiplier`, `roles[1]`) or a query/path parameter name |
| `retryAfterSeconds` | Only on 429: wait this long before retrying (also sent as the `Retry-After` header)          |

The same shape is used for every failure: errors from the security layer (missing or expired token),
unknown URLs, wrong HTTP methods, malformed JSON and unexpected server errors. Messages never contain
stack traces, SQL or Java class names.

## TypeScript

```ts
export interface FieldError { field: string; message: string }

export interface ApiError {
  code: string;
  status: number;
  path: string;
  fields: FieldError[];
  retryAfterSeconds?: number;
}

export interface PageMeta {
  page: number; size: number; totalElements: number; totalPages: number;
  hasNext: boolean; hasPrevious: boolean;
}

export type ApiResponse<T> =
  | { success: true;  message: string; data: T;    meta?: PageMeta; timestamp: string }
  | { success: false; message: string; data: null; error: ApiError; timestamp: string };
```

Example with `fetch`:

```ts
async function api<T>(path: string, init?: RequestInit): Promise<ApiResponse<T>> {
  const res = await fetch(`/api/v1${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${accessToken}`, ...init?.headers },
  });
  return res.json();            // every response, including errors, has a JSON body
}

const res = await api<Chama>("/chamas", { method: "POST", body: JSON.stringify(form) });
if (res.success) {
  navigate(`/chamas/${res.data.id}`);
} else {
  toast.error(res.message);
  res.error.fields.forEach(f => setFieldError(f.field, f.message));   // e.g. react-hook-form setError
  if (res.error.code === "AUTH_INVALID_ACCESS_TOKEN") await refreshAndRetry();
}
```

## Error codes

Branch on `error.code`, never on `message`.

**Request problems** (any endpoint)

| Code                        | Status | When                                                     |
|-----------------------------|--------|----------------------------------------------------------|
| `VALIDATION_FAILED`         | 400    | Invalid fields; see `error.fields`                       |
| `MALFORMED_REQUEST`         | 400    | Body missing or not valid JSON                           |
| `INVALID_PARAMETER`         | 400    | A path or query parameter has the wrong type, e.g. not a UUID |
| `MISSING_PARAMETER`         | 400    | A required query parameter is missing                    |
| `UNAUTHENTICATED`           | 401    | No access token sent                                     |
| `AUTH_INVALID_ACCESS_TOKEN` | 401    | Access token expired or invalid: refresh it, then retry  |
| `ACCESS_DENIED`             | 403    | Missing chama permission, or not an active member        |
| `TENANT_REQUIRED`           | 400    | A public chama endpoint was called without `X-Chama-Code` |
| `TENANT_NOT_FOUND`          | 404    | `X-Chama-Code` names no chama: check the code            |
| `NOT_FOUND`                 | 404    | Unknown URL                                              |
| `METHOD_NOT_ALLOWED`        | 405    | Wrong HTTP method for the URL                            |
| `UNSUPPORTED_MEDIA_TYPE`    | 415    | Body not sent as `application/json`                      |
| `CONCURRENT_MODIFICATION`   | 409    | Someone else changed the record; reload and retry        |
| `DATA_CONFLICT`             | 409    | Conflicts with existing data                             |
| `INTERNAL_ERROR`            | 500    | Unexpected server error                                  |

**Auth and account**

| Code                              | Status | When                                                        |
|-----------------------------------|--------|-------------------------------------------------------------|
| `AUTH_INVALID_CREDENTIALS`        | 401    | Wrong email or password                                     |
| `AUTH_ACCOUNT_LOCKED`             | 429    | Too many failed logins; see `error.retryAfterSeconds`       |
| `AUTH_ACCOUNT_DISABLED`           | 403    | Account disabled                                            |
| `AUTH_EMAIL_NOT_VERIFIED`         | 403    | Action needs a verified email                               |
| `AUTH_INVALID_TOKEN`              | 401    | Refresh token invalid, expired or already used: log in again |
| `AUTH_LINK_INVALID`               | 400    | Verification or reset link invalid, expired or already used |
| `AUTH_USER_ALREADY_EXISTS`        | 409    | Email already registered                                    |
| `AUTH_CURRENT_PASSWORD_INCORRECT` | 400    | Change password: current password wrong                     |
| `AUTH_WEAK_PASSWORD`, `AUTH_PASSWORD_TOO_LONG`, `AUTH_INVALID_EMAIL`, `INVALID_PHONE_NUMBER` | 400 | Registration input rejected |

**Chamas, licences and members**

| Code                          | Status | When                                                          |
|-------------------------------|--------|---------------------------------------------------------------|
| `CHAMA_NOT_FOUND`             | 404    | No such chama                                                 |
| `CHAMA_CODE_TAKEN`            | 409    | Registering a chama with a code that is already in use        |
| `CHAMA_INVALID_CODE`          | 400    | Chama code malformed (3–30 letters, digits, hyphens) or reserved |
| `CHAMA_INVALID_NAME`, `CHAMA_INVALID_DESCRIPTION`, `CHAMA_INVALID_SETTINGS` | 400 | Chama input rejected |
| `LICENSE_EXPIRED`             | 403    | The chama's licence has expired; only viewing the chama, its licence and `members/me` still works |
| `LICENSE_SUSPENDED`           | 403    | The platform provider suspended the licence                   |
| `LICENSE_FEATURE_NOT_INCLUDED`| 403    | The licence's plan does not include this feature              |
| `LICENSE_MEMBER_LIMIT_REACHED`| 422    | Approving another member would exceed the licence's member limit |
| `MEMBER_NOT_FOUND`            | 404    | No such membership in this chama                              |
| `MEMBER_ALREADY_EXISTS`       | 409    | Inviting an email that already has an account in this chama   |
| `MEMBER_SELF_MANAGEMENT`      | 403    | Officials cannot change their own roles or status             |
| `MEMBER_INVALID_STATE`        | 422    | Action not allowed in the membership's current status         |
| `MEMBER_LAST_CHAIRPERSON`     | 422    | The chama must keep at least one chairperson                  |
| `ROLE_PERMISSIONS_LOCKOUT`    | 422    | The chairperson role must keep role and permission management |

**Ledger**

| Code                             | Status | When                                                          |
|----------------------------------|--------|---------------------------------------------------------------|
| `LEDGER_UNBALANCED_ENTRY`        | 400    | Debits do not equal credits, or fewer than two lines           |
| `LEDGER_INVALID_ENTRY`           | 400    | Missing description, zero amount, future date, or a line with both/neither debit and credit |
| `LEDGER_MEMBER_MISMATCH`         | 400    | A member account line without `memberUserId`, a member on another account, or an unknown member |
| `LEDGER_CURRENCY_MISMATCH`       | 400    | Amount in a currency other than the chama's                    |
| `LEDGER_INVALID_ACCOUNT`         | 400    | Malformed account code or name, or deactivating a system account |
| `LEDGER_ACCOUNT_NOT_FOUND`       | 404    | No account with that code or id in this chama                  |
| `LEDGER_ENTRY_NOT_FOUND`         | 404    | No such journal entry in this chama                            |
| `LEDGER_ACCOUNT_CODE_TAKEN`      | 409    | The account code is already used                               |
| `LEDGER_ALREADY_REVERSED`        | 409    | The entry has already been reversed                            |
| `LEDGER_ACCOUNT_INACTIVE`        | 422    | Posting to a deactivated account                               |
| `LEDGER_CANNOT_REVERSE_REVERSAL` | 422    | Reversing a reversal entry                                     |

**Contributions** (need the `CONTRIBUTIONS` licence feature)

| Code                               | Status | When                                                          |
|------------------------------------|--------|---------------------------------------------------------------|
| `CONTRIBUTION_INVALID_PAYMENT`     | 400    | Zero amount, future date, or an M-Pesa/bank payment without its reference |
| `CONTRIBUTION_SELF_RECORDING`      | 403    | An official recording or reversing their own contribution     |
| `CONTRIBUTION_MEMBER_NOT_FOUND`    | 404    | The person is not an active or suspended member               |
| `CONTRIBUTION_NOT_FOUND`           | 404    | No such payment in this chama                                 |
| `CONTRIBUTION_PERIOD_NOT_FOUND`    | 404    | No such contribution period                                   |
| `CONTRIBUTION_DUPLICATE_REFERENCE` | 409    | That M-Pesa code or bank slip has already been recorded       |
| `CONTRIBUTION_ALREADY_REVERSED`    | 409    | The payment has already been reversed                         |

**Penalties** (need the `PENALTIES` licence feature)

| Code                          | Status | When                                                          |
|-------------------------------|--------|---------------------------------------------------------------|
| `PENALTY_INVALID`             | 400    | Zero or over-the-outstanding payment, a fine without a reason, a waiver without a reason, a future date, or an M-Pesa/bank payment without its reference |
| `PENALTY_SELF_HANDLING`       | 403    | An official fining themselves, waiving their own penalty or recording their own payment |
| `PENALTY_NOT_FOUND`           | 404    | No such penalty in this chama                                  |
| `PENALTY_MEMBER_NOT_FOUND`    | 404    | The person is not an active or suspended member                |
| `PENALTY_CLOSED`              | 409    | The penalty is already paid or waived                          |
| `PENALTY_DUPLICATE_REFERENCE` | 409    | That M-Pesa code or bank slip has already been recorded for a penalty |

**Loans** (need the `LOANS` licence feature)

| Code                            | Status | When                                                          |
|---------------------------------|--------|---------------------------------------------------------------|
| `LOAN_INVALID`                  | 400    | Zero amount, a term outside 1-36 months, no purpose, too few or repeated guarantors, guarantors on a loan that needs none, a rejection without a reason, a future date, an over-the-outstanding repayment, or an M-Pesa/bank transaction without its reference |
| `LOAN_SELF_HANDLING`            | 403    | An official approving, rejecting, disbursing or recording repayments on their own loan |
| `LOAN_GUARANTOR_CANNOT_APPROVE` | 403    | An official approving a loan they guarantee                    |
| `LOAN_NOT_BORROWER`             | 403    | Someone other than the borrower cancelling or adding guarantors |
| `LOAN_NOT_GUARANTOR`            | 403    | Answering a guarantee request you were not sent                |
| `LOAN_NOT_FOUND`                | 404    | No such loan in this chama                                     |
| `LOAN_MEMBER_NOT_FOUND`         | 404    | A guarantor is not an active member                            |
| `LOAN_WRONG_STATUS`             | 409    | The step does not fit the loan's status, e.g. approving before guarantors accept, or disbursing twice |
| `LOAN_ALREADY_APPROVED`         | 409    | The same official approving twice                              |
| `LOAN_GUARANTOR_RESPONDED`      | 409    | A guarantor answering again                                    |
| `LOAN_DUPLICATE_REFERENCE`      | 409    | That M-Pesa code or bank slip has already been used for a loan |
| `LOAN_NOT_ELIGIBLE`             | 422    | More than the limit (a multiple of savings), too short a membership, no savings, not active, or another loan still in progress. The message lists every reason |
| `LOAN_INSUFFICIENT_FUNDS`       | 422    | The account the loan is paid from does not hold enough         |

**Expenses** (need the `EXPENSES` licence feature)

| Code                          | Status | When                                                          |
|-------------------------------|--------|---------------------------------------------------------------|
| `EXPENSE_INVALID`             | 400    | Zero amount, no payee or description, a future date, a rejection without a reason, or an M-Pesa/bank payment without its reference |
| `EXPENSE_ACCOUNT_INVALID`     | 400    | The account code does not exist, is not an expense account, or is inactive |
| `EXPENSE_INVESTMENT_INVALID`  | 400    | The investment the expense is charged to does not exist or is not active |
| `EXPENSE_SELF_APPROVAL`       | 403    | Whoever recorded the expense approving or rejecting it          |
| `EXPENSE_NOT_REQUESTER`       | 403    | Someone other than whoever recorded it cancelling it            |
| `EXPENSE_NOT_FOUND`           | 404    | No such expense in this chama                                   |
| `EXPENSE_WRONG_STATUS`        | 409    | The step does not fit the status, e.g. paying before approval or paying twice |
| `EXPENSE_ALREADY_APPROVED`    | 409    | The same official approving twice                               |
| `EXPENSE_DUPLICATE_REFERENCE` | 409    | That M-Pesa code or bank slip has already been used for an expense |
| `EXPENSE_INSUFFICIENT_FUNDS`  | 422    | The account the expense is paid from does not hold enough       |

**Investments** (need the `INVESTMENTS` licence feature)

| Code                             | Status | When                                                          |
|----------------------------------|--------|---------------------------------------------------------------|
| `INVESTMENT_INVALID`             | 400    | No name or type, zero amount, a maturity date not in the future, a future date, a withdrawal of the whole book value (close instead), REINVESTED anywhere but income, or an M-Pesa/bank transaction without its reference |
| `INVESTMENT_SELF_APPROVAL`       | 403    | Whoever proposed the investment approving or rejecting it      |
| `INVESTMENT_NOT_PROPOSER`        | 403    | Someone other than whoever proposed it cancelling it           |
| `INVESTMENT_NOT_FOUND`           | 404    | No such investment in this chama                               |
| `INVESTMENT_WRONG_STATUS`        | 409    | The step does not fit the status, e.g. funding before approval, or income on a closed investment |
| `INVESTMENT_ALREADY_APPROVED`    | 409    | The same official approving twice                              |
| `INVESTMENT_DUPLICATE_REFERENCE` | 409    | That M-Pesa code or bank slip has already been used for an investment |
| `INVESTMENT_INSUFFICIENT_FUNDS`  | 422    | The account it is funded from does not hold enough             |
