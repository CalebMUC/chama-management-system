# API response format

Every endpoint returns the same JSON envelope, for successes and errors alike. A client
checks one field, `success`, and then reads either `data` or `error`.

Swagger UI (`http://localhost:8080/swagger-ui.html`) shows the exact `data` shape of each endpoint.

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

**Chamas and members**

| Code                          | Status | When                                                          |
|-------------------------------|--------|---------------------------------------------------------------|
| `CHAMA_NOT_FOUND`             | 404    | No such chama                                                 |
| `CHAMA_EMAIL_NOT_VERIFIED`    | 403    | Creating a chama needs a verified email                       |
| `CHAMA_INVALID_NAME`, `CHAMA_INVALID_DESCRIPTION`, `CHAMA_INVALID_SETTINGS` | 400 | Chama input rejected |
| `MEMBER_NOT_FOUND`            | 404    | No such membership in this chama                              |
| `MEMBER_ALREADY_EXISTS`       | 409    | That email is already a member or has a pending invitation    |
| `MEMBER_INVITATION_NOT_YOURS` | 403    | Accepting or declining someone else's invitation              |
| `MEMBER_EMAIL_NOT_VERIFIED`   | 403    | Accepting an invitation needs a verified email                |
| `MEMBER_SELF_MANAGEMENT`      | 403    | Officials cannot change their own roles or status             |
| `MEMBER_INVALID_STATE`        | 422    | Action not allowed in the membership's current status         |
| `MEMBER_LAST_CHAIRPERSON`     | 422    | The chama must keep at least one chairperson                  |
| `ROLE_PERMISSIONS_LOCKOUT`    | 422    | The chairperson role must keep role and permission management |
