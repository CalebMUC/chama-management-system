# ADR-007: Multi-Tenancy in a Shared Database, with a Licence per Chama

**Status:** Accepted  
**Date:** 2026-11  
**Author:** Caleb Muchiri

## Context

The platform serves many chamas (e.g. Mokica Chama, Jamii Chama), each with its own members and
operations, sold like a multi-tenant POS: every chama is a customer with its own licence. Running
one database per chama would multiply hosting cost (see ADR-006); a leak of one chama's member or
money data to another would be unacceptable.

## Decision

1. **Every chama is a tenant; all tenants share one PostgreSQL database.** Each tenant-owned row
   carries `tenant_id` (the chama's id). New tables owned by a chama must have it too.
2. **A user belongs to exactly one chama.** Email is unique per chama, so a person in two chamas
   holds two separate accounts. Platform administrators belong to a platform tenant
   (`00000000-0000-0000-0000-000000000001`), not to a chama.
3. **The tenant of a request is never taken from a URL.** Logged-in requests use the `tenant_id`
   claim of the signed access token; public auth endpoints name the chama with the
   `X-Chama-Code` header; requests with neither are platform requests. Chama URLs carry no chama
   id (`/api/v1/members`, not `/api/v1/chamas/{id}/members`).
4. **Isolation is enforced twice:**
   - *Application:* Hibernate's `@TenantId` filters every query and stamps every insert.
   - *Database:* PostgreSQL row-level security on every tenant table. The application connects as
     the owner but switches each connection to the restricted role `chama_app` and sets
     `app.tenant_id` (`TenantAwareDataSource`). Without a tenant, nothing is visible (fail closed).
     Flyway migrates through a separate owner connection.
5. **The tenant is chosen before a transaction starts** (`TenantContext.callAs`), because the
   database session and the Hibernate session capture it at transaction begin. Cross-tenant work
   (platform listing, housekeeping) runs in `TenantContext.callAsSystem`, which bypasses RLS.
6. **Only platform administrators add chamas** (`POST /api/v1/platform/chamas`), like a POS
   vendor provisioning a customer. There is no public chama signup.
7. **Every chama has one licence** (plan, validity, member limit, features). Onboarding issues a
   30-day trial or the terms the platform administrator sets; they later renew, upgrade or
   suspend it. An expired or suspended licence blocks the chama's operations (`LicenseInterceptor`); `@RequiresFeature` gates licensed
   features; licence changes are written to the chama's own audit log.

## Consequences

- A missed `WHERE tenant_id = ?` in application code cannot leak data: RLS still applies.
- `chama_app` has no UPDATE/DELETE on `audit.audit_log`, strengthening its append-only guarantee.
- Every new module's migration must add `tenant_id`, enable RLS with the standard
  `tenant_isolation` policy, and grant its schema to `chama_app`.
- Tests that inspect the database directly must use the owner connection (`OwnerJdbc`).
- Moving a single chama to its own database later (e.g. a large customer) remains possible: its
  rows are identified by `tenant_id`.
- In production the database user should not be a superuser; it must be granted `chama_app`.
