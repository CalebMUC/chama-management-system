<div align="center">

# Chama Management System

**A multi-tenant platform for running Kenyan investment groups (Chamas): contributions, loans, penalties, expenses, investments and double-entry books**

[![Java](https://img.shields.io/badge/Java-25-ED8B00?style=flat&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?style=flat&logo=spring-boot)](https://spring.io/projects/spring-boot)
[![Spring Modulith](https://img.shields.io/badge/Spring_Modulith-2.1-6DB33F?style=flat&logo=spring)](https://spring.io/projects/spring-modulith)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-316192?style=flat&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat&logo=docker&logoColor=white)](https://www.docker.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Backend CI/CD](https://github.com/CalebMUC/chama-management-system/actions/workflows/backend-ci-cd.yml/badge.svg)](https://github.com/CalebMUC/chama-management-system/actions/workflows/backend-ci-cd.yml)

[Business Context](#-business-context) •
[Features](#-features) •
[Architecture](#%EF%B8%8F-architecture) •
[Quick Start](#-quick-start) •
[API](#-api) •
[Security](#-security-model) •
[Testing](#-testing) •
[Roadmap](#%EF%B8%8F-roadmap)

</div>

---

## 📋 Business Context

> **Chama** *(noun, Swahili)*: a community savings and investment group, common across Kenya and East Africa. Members pool money every month, lend to each other from the pooled savings, and invest together.

There are an estimated **300,000+ registered Chamas in Kenya**, managing over **KES 500 billion** in assets. Most are still run on WhatsApp messages and paper ledgers, which leads to missing records, disputes over balances, and no audit trail.

**This system replaces the notebook.** One deployment hosts many chamas. Each chama is an isolated tenant with its own members, rules, books and licence.

### What a chama does, and what the system records

```
Member registers with the chama code ──► an official approves them (member number assigned)
│
▼
Pays the contribution every period (e.g. KES 5,000/month), recorded with its M-Pesa code
│
├──► Split across savings products (Deposits, Share capital, Welfare, …) and posted to the ledger
│
├──► Late payments are penalised automatically (nightly)
│
├──► Member borrows up to a multiple of their savings
│     ├──► guarantors accept, a quorum of officials approves
│     ├──► treasurer disburses, a monthly repayment schedule starts
│     └──► repayments with flat interest grow the chama's income
│
├──► Officials record expenses; other officials approve them before they are paid
│
└──► Surplus is invested (MMF, T-bills, land, …): approved, funded, income and valuations tracked
```

### Core business rules (per-chama defaults, all configurable)

| Rule | Default |
|------|---------|
| **Contribution** | KES 5,000 per month, due on the 5th |
| **Late penalty** | 2% of the contribution per late period, after 5 grace days |
| **Loan limit** | 3× the member's loan-backing savings, after 3 months of membership |
| **Loan interest** | 10% flat for the agreed term, repaid in equal monthly instalments (max 36 months) |
| **Guarantors** | Loans above KES 50,000 need 2 guarantors from active members |
| **Approval quorum** | 2 distinct officials approve every loan, expense and investment |
| **Separation of duties** | Nobody records, approves or collects on their own money; whoever records an expense or proposes an investment cannot approve it |

Loan products (e.g. an emergency loan with its own terms) and savings products can be added per chama.

---

## ✨ Features

| Area | What is built |
|------|---------------|
| **Platform administration** | Onboard chamas with a unique login code, first administrator and licence (trial, basic, standard, premium); renew, upgrade or suspend licences |
| **Licensing** | Plan, expiry, member limit and feature list per chama; expired or suspended licences block the chama; features gate whole modules |
| **Auth** | Register, login, refresh-token rotation with reuse detection, logout / logout everywhere, login lockout, email verification, forgot / reset / change password |
| **Members & roles** | Applications, approval, suspension, invitations; roles (member, treasurer, secretary, chairperson, auditor) mapped to 33 fine-grained permissions that each chama can re-map |
| **Contributions** | Automatic periods (monthly or weekly), payments by cash / bank / M-Pesa, allocation across savings products, arrears, advances, late periods, reversals |
| **Savings products & member accounts** | Deposits, share capital, welfare and custom products; an account per member per product with statements |
| **Penalties** | Nightly late-contribution penalties, fines, part-payments, waivers |
| **Loans** | Eligibility, loan products, guarantors, quorum approval, disbursement, repayment schedule, repayments |
| **Expenses** | Maker-checker approval, payment through the ledger, optional link to an investment |
| **Investments** | Proposal, quorum approval, funding, deposits, income (paid out or reinvested), withdrawals, valuations, closure, profitability and portfolio |
| **Ledger** | Double-entry books per chama: chart of accounts, journal, manual entries, reversals, statements, trial balance |
| **Reports & dashboards** | Financial position, income statement, cash book, contribution matrix, loan ageing and PAR30, savings register, member statements; official and member dashboards |
| **Audit log** | Append-only record of every accountable action |
| **Opening-balance import** | Excel template → preview with row-level problems → one-time, all-or-nothing import |
| **Email notifications** | Verification, password reset, invitations, welcome, contribution receipts, loan and penalty notices |

---

## 🏗️ Architecture

The backend is a **modular monolith** ([ADR-006](docs/architecture/ADR-006-modular-monolith-over-microservices.md)): one Spring Boot application and one PostgreSQL database, split into modules with microservice-style boundaries. A cost analysis showed 11 separately hosted services would cost about 10× more at this stage; the module boundaries keep extraction possible later.

```
                 Web / mobile client
                         │  HTTPS, JSON, Bearer JWT
┌────────────────────────▼─────────────────────────────────────────────┐
│ Spring Boot application  (port 8080)                                 │
│                                                                      │
│  Security filter chain: JWT validation → tenant from token           │
│  Licence interceptor: licence valid? feature included?               │
│  Method security: @RequiresChamaPermission / @RequiresChamaMembership│
│                                                                      │
│  ┌──────┐ ┌───────┐ ┌────────┐ ┌──────────────┐ ┌─────────┐ ┌──────┐ │
│  │ auth │ │ chama │ │ member │ │ contribution │ │ account │ │ loan │ │
│  └──────┘ └───────┘ └────────┘ └──────────────┘ └─────────┘ └──────┘ │
│  ┌─────────┐ ┌─────────┐ ┌────────────┐ ┌────────┐ ┌────────────┐    │
│  │ penalty │ │ expense │ │ investment │ │ report │ │ dataimport │    │
│  └─────────┘ └─────────┘ └────────────┘ └────────┘ └────────────┘    │
│  ┌──────────────────────┐ ┌───────┐ ┌──────────────┐                 │
│  │ transaction (ledger) │ │ audit │ │ notification │                 │
│  └──────────────────────┘ └───────┘ └──────────────┘                 │
│                                                                      │
│  Domain events: Spring Modulith, persisted in an outbox table and    │
│  delivered after commit (emails are sent asynchronously, retried)    │
└───────────┬─────────────────────────────────────────┬────────────────┘
            │                                         │
   PostgreSQL 16                                SMTP server
   one database, one schema per module,         (Mailpit locally)
   row-level security per chama
```

### Modules

Each module lives in `backend/src/main/java/ke/chama/modules/<module>` and is laid out in hexagonal layers: `api` (what other modules may use), `application` (use cases and ports), `domain` (model, events, exceptions), `infrastructure` (persistence, scheduling, adapters) and `presentation` (controllers and DTOs).

| Module | Schema | Responsibility |
|--------|--------|----------------|
| `auth` | `auth` | Users, passwords, JWT access tokens, refresh tokens, login attempts, email links |
| `chama` | `chama` | Chamas (tenants), their rules, licences, platform onboarding |
| `member` | `member` | Memberships, roles, role → permission mapping, permission checks |
| `contribution` | `contribution` | Contribution periods, payments, allocation, standings and arrears |
| `account` | `account` | Savings products and member accounts |
| `penalty` | `penalty` | Late-contribution penalties, fines, payments, waivers |
| `loan` | `loan` | Loan products, applications, guarantors, approvals, schedules, repayments |
| `expense` | `expense` | Expenses with maker-checker approval |
| `investment` | `investment` | Investments, transactions, valuations, performance |
| `transaction` | `ledger` | Double-entry ledger: chart of accounts, journal entries |
| `report` | (reads others) | Financial statements, reports and dashboards |
| `audit` | `audit` | Append-only audit log |
| `dataimport` | `dataimport` | Opening-balance import from Excel |
| `notification` | (none) | Emails, triggered by other modules' events |

Shared building blocks (`ke.chama.shared`): `Money`, `PhoneNumber`, the response envelope and exception handling, security annotations and tenancy.

Boundaries are enforced by tests: Spring Modulith verifies that modules only use each other's `api` packages and published events (`ModularityTests`), and ArchUnit checks the layering rules (`ArchitectureTests`).

### Multi-tenancy

Every chama is a tenant in one shared database ([ADR-007](docs/architecture/ADR-007-multi-tenancy-shared-database.md)):

- A user belongs to exactly one chama. Logged-in requests take the chama from the signed access token; public auth endpoints name it with the `X-Chama-Code` header. URLs never carry a chama id.
- Isolation is enforced twice: Hibernate's tenant filter on every query, and **PostgreSQL row-level security** on every tenant table. The application switches each connection to the restricted `chama_app` role, so a missed filter in code still cannot leak another chama's data.
- Platform administrators belong to a separate platform tenant and are the only ones who can create chamas.

### Key decisions

| Decision | Choice | Why |
|----------|--------|-----|
| **Deployment** | Modular monolith ([ADR-006](docs/architecture/ADR-006-modular-monolith-over-microservices.md)) | ~10× cheaper to host at early scale; modules stay extractable |
| **Tenancy** | Shared database, `tenant_id` + row-level security ([ADR-007](docs/architecture/ADR-007-multi-tenancy-shared-database.md)) | One database to run; isolation guaranteed by PostgreSQL |
| **Events** | Spring Modulith application events with a JDBC outbox | Reliable after-commit side effects without a broker |
| **Auth** | Stateless JWT (15 min) + rotating refresh tokens (7 days) stored hashed in PostgreSQL | Revocable sessions, theft detection on reuse |
| **Authorisation** | Per-chama permissions checked on every request, not stored in the token | Role changes and suspensions apply immediately |
| **Money** | `BIGINT` minor units (KES cents) via the `Money` value type | No floating-point errors |
| **Rates** | Integer basis points (100 = 1%) | Exact percentages |
| **Books** | Double-entry ledger; entries are reversed, never edited or deleted | Auditability; balance sheet always balances |
| **Primary keys** | UUID | No enumeration across tenants |
| **API** | URI versioning (`/api/v1/`), one JSON envelope for every response | Predictable clients |

---

## 🧰 Tech Stack

| Concern | Technology |
|---------|------------|
| Language / runtime | Java 25 |
| Framework | Spring Boot 4.1 (Web MVC, Security, OAuth2 Resource Server, Data JPA, Validation, Mail, Actuator) |
| Modularity & events | Spring Modulith 2.1 (JDBC event publication registry) |
| Database | PostgreSQL 16, Flyway migrations, row-level security |
| Spreadsheets | Apache POI (opening-balance import) |
| API docs | springdoc-openapi (Swagger UI) |
| Testing | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), Spring Modulith test, ArchUnit |
| Build | Maven wrapper (`./mvnw`) |
| Packaging | Multi-stage Docker image (layered JAR, non-root user) |
| CI/CD | GitHub Actions → GitHub Container Registry |
| Local infrastructure | Docker Compose: PostgreSQL, Mailpit, RabbitMQ, Redis |

RabbitMQ and Redis are provisioned and the application connects to them, but no module uses them yet. Events are delivered in-process; RabbitMQ is the planned transport when a module is extracted (ADR-006).

---

## 🚀 Quick Start

### Prerequisites

- JDK 25
- Docker with Docker Compose
- (Maven is not needed: use the wrapper `./mvnw`, or `mvnw.cmd` on Windows)

### 1. Start the infrastructure

```bash
git clone https://github.com/CalebMUC/chama-management-system.git
cd chama-management-system

docker compose -f infrastructure/docker/docker-compose.yml up -d
```

This starts PostgreSQL, Mailpit (catches all outgoing email), RabbitMQ and Redis on non-default ports so they don't clash with local installs.

### 2. Run the backend

```bash
cd backend
PLATFORM_ADMIN_EMAIL=admin@platform.local \
PLATFORM_ADMIN_PASSWORD='Admin12345' \
./mvnw spring-boot:run
```

Flyway creates the schemas on first start. The two `PLATFORM_ADMIN_*` variables create the first platform administrator, who can then onboard chamas. Everything else has local defaults.

### 3. Open it

| URL | What |
|-----|------|
| http://localhost:8080/swagger-ui.html | Swagger UI (try every endpoint) |
| http://localhost:8080/v3/api-docs | OpenAPI document |
| http://localhost:8080/actuator/health | Health check |
| http://localhost:18025 | Mailpit: every email the app sends (verification links, receipts, …) |
| http://localhost:15672 | RabbitMQ management (guest / guest) |
| `localhost:15432` | PostgreSQL `chama_db` (postgres / postgres) |

### 4. Walk through the core flow

```bash
API=http://localhost:8080/api/v1

# 1. Platform admin logs in (no chama code)
ADMIN=$(curl -s -X POST $API/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@platform.local","password":"Admin12345"}' | jq -r '.data.accessToken')

# 2. Onboard a chama with its first administrator (chairperson, member #1). 30-day trial licence.
curl -s -X POST $API/platform/chamas -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{
  "chama": { "name": "Mokica Investment Group", "code": "mokica",
             "admin": { "email": "chair@mokica.test", "phoneNumber": "0712345678", "password": "Chair12345",
                        "firstName": "Grace", "lastName": "Achieng" } } }' | jq '.message'

# 3. A member registers with the chama code (their application waits for approval)
curl -s -X POST $API/auth/register -H 'X-Chama-Code: mokica' -H 'Content-Type: application/json' -d '{
  "email": "jane@mokica.test", "phoneNumber": "0722000111", "password": "Jane12345",
  "firstName": "Jane", "lastName": "Wanjiku" }' | jq '.message'

# 4. The chairperson logs in with the chama code and approves the application
CHAIR=$(curl -s -X POST $API/auth/login -H 'X-Chama-Code: mokica' -H 'Content-Type: application/json' \
  -d '{"email":"chair@mokica.test","password":"Chair12345"}' | jq -r '.data.accessToken')

APPLICANT=$(curl -s "$API/members?status=PENDING_APPROVAL" -H "Authorization: Bearer $CHAIR" | jq -r '.data[0]')
curl -s -X POST "$API/members/$(echo "$APPLICANT" | jq -r '.membershipId')/approve" \
  -H "Authorization: Bearer $CHAIR" | jq '.message'

# 5. Record Jane's contribution (KES 5,000 = 500000 cents) by M-Pesa
curl -s -X POST $API/contributions -H "Authorization: Bearer $CHAIR" -H 'Content-Type: application/json' -d '{
  "memberUserId": "'"$(echo "$APPLICANT" | jq -r '.userId')"'",
  "amountMinor": 500000, "method": "MPESA", "reference": "QK7H2XY9ZB" }' | jq '.data.allocations'

# 6. The chama dashboard
curl -s $API/dashboard -H "Authorization: Bearer $CHAIR" | jq '.data.cash, .data.contributions'
```

Open Mailpit to see the verification emails and Jane's contribution receipt.

### Configuration

Every setting has a local default in [`application.yaml`](backend/src/main/resources/application.yaml); deployed environments set these environment variables:

| Variable | Purpose | Local default |
|----------|---------|---------------|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection (also used by Flyway) | `jdbc:postgresql://localhost:15432/chama_db`, `postgres`, `postgres` |
| `JWT_SECRET` | HS256 signing key, at least 32 bytes. **Required under the `prod` profile**: startup fails on the development secret | development-only value |
| `PLATFORM_ADMIN_EMAIL`, `PLATFORM_ADMIN_PASSWORD` | Creates the first platform administrator at start-up if both are set | unset |
| `FRONTEND_BASE_URL` | Where emailed links point (`/verify-email`, `/reset-password`, `/join`) | `http://localhost:3000` |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_STARTTLS`, `MAIL_FROM` | SMTP | Mailpit on `localhost:11025` |
| `REDIS_HOST`, `REDIS_PORT` | Redis | `localhost:16379` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | RabbitMQ | `localhost:15673`, `guest`, `guest` |
| `SPRING_PROFILES_ACTIVE=prod` | Production: Swagger UI and API docs off, development JWT secret refused | — |

Other settings in `application.yaml`: business time zone (`Africa/Nairobi`), token lifetimes (15 min / 7 days), login lockout (5 failures in 15 min), trial length (30 days), upload limit (5 MB).

---

## 📡 API

All endpoints are under `/api/v1`, take and return JSON, and use one response envelope:

```json
{ "success": true,  "message": "Contribution recorded", "data": { … }, "timestamp": "2026-11-08T07:15:02Z" }
{ "success": false, "message": "One or more fields are invalid", "data": null,
  "error": { "code": "VALIDATION_FAILED", "status": 400, "path": "/api/v1/contributions",
             "fields": [ { "field": "amountMinor", "message": "must be greater than 0" } ] },
  "timestamp": "…" }
```

Paged lists add `meta` (`page`, `size`, `totalElements`, `totalPages`, `hasNext`, `hasPrevious`). Clients branch on the stable `error.code`; `message` is always safe to show to users. Amounts are integer minor units (`500000` = KES 5,000.00) and rates are basis points.

| Area | Base path |
|------|-----------|
| Auth & account | `/auth` (register, login, refresh, logout, logout-all, me, email verification, password forgot/reset/change) |
| My chama | `/chama`, `/chama/settings`, `/chama/license` |
| Members & roles | `/members`, `/roles` |
| Contributions | `/contributions` (payments, periods, standings, me) |
| Savings | `/savings-products`, `/member-accounts` |
| Penalties | `/penalties` |
| Loans | `/loans`, `/loan-products` |
| Expenses | `/expenses` |
| Investments | `/investments` |
| Ledger | `/ledger` (accounts, entries, statements, trial balance) |
| Dashboards & reports | `/dashboard`, `/reports` |
| Audit | `/audit-log` |
| Import | `/imports/opening-balances` |
| Platform admin | `/platform/chamas` |

Documentation:

- **Swagger UI** on a running backend: `/swagger-ui.html` (disabled under `prod`)
- **Response format**: [`docs/api/response-format.md`](docs/api/response-format.md)
- **Postman collection**: [`docs/api/postman/`](docs/api/postman/)
- **Frontend build guide, full endpoint reference and TypeScript types**: [`docs/frontend/`](docs/frontend/)

The API has no CORS configuration; serve a browser frontend from the same origin (reverse proxy, or a dev-server proxy locally).

---

## 🔐 Security Model

**Two levels of role:**

| Level | Roles | Where it applies |
|-------|-------|------------------|
| Platform | `ROLE_ADMIN` (platform administrator), `ROLE_USER` | `/api/v1/platform/**`: onboarding chamas, licences |
| Chama | `MEMBER`, `TREASURER`, `SECRETARY`, `CHAIRPERSON`, `AUDITOR`, `ADMIN` (a member can hold several) | Everything inside a chama |

Endpoints check **permissions**, never role names. Each chama starts with a default role → permission mapping (treasurer records money, secretary handles membership and approves spending, chairperson can do everything, auditor sees everything and changes nothing, admin runs operations such as members, roles, settings and products without touching money) and the chairperson can re-map it. Permissions are read from the database on every request, so suspensions and role changes apply immediately. Viewing your own records needs only an active membership.

**Sessions:** JWT access tokens expire after **15 minutes**. Refresh tokens last **7 days**, are stored hashed in PostgreSQL, and rotate on every use; presenting an already-used refresh token revokes all of the user's sessions. Five failed logins within 15 minutes lock the email for 15 minutes. Passwords are BCrypt-hashed (8–72 characters, a letter and a digit).

**Accountability:** maker-checker approval with a configurable quorum; no one acts on their own records; every change is written to an append-only audit log (the application's database role cannot update or delete it).

**Tenant isolation:** PostgreSQL row-level security on every chama-owned table (see [Multi-tenancy](#multi-tenancy)).

---

## 🗃️ Database

One PostgreSQL database, `chama_db`, with one schema per module: `auth`, `chama`, `member`, `contribution`, `account`, `penalty`, `loan`, `expense`, `investment`, `ledger`, `audit`, `dataimport`, plus Spring Modulith's event publication table.

- Migrations live in [`backend/src/main/resources/db/migration`](backend/src/main/resources/db/migration) (`V1` … `V20`) and run at start-up through a separate owner connection. Hibernate only validates the schema.
- Every chama-owned table has `tenant_id` and the standard `tenant_isolation` row-level security policy; new module migrations must add both and grant the schema to `chama_app`.
- In production, the database user must not be a superuser and must be granted the `chama_app` role (created by `V9`).

### Scheduled jobs

| Job | When (Africa/Nairobi) | What |
|-----|-----------------------|------|
| Late-contribution penalties | 01:00 daily | Penalises every late period not yet penalised, for every chama |
| Refresh-token cleanup | 02:30 daily | Deletes expired refresh tokens |

Contribution periods are opened automatically (and missed ones caught up) whenever contributions are used.

---

## 🧪 Testing

```bash
cd backend
./mvnw verify
```

Integration tests start their own PostgreSQL with **Testcontainers**, so Docker must be running. They also expect Redis and RabbitMQ on the docker-compose ports (start the compose stack first).

| Kind | Examples |
|------|----------|
| Domain unit tests | `MoneyTest`, `LoanTest`, `MemberStandingTest`, `PaymentAllocationTest`, `FinancialStatementsTest`, `LoanAgingTest` |
| Integration tests (HTTP → database) | `AuthFlowIntegrationTests`, `ContributionIntegrationTests`, `LoanIntegrationTests`, `LedgerIntegrationTests`, `ReportIntegrationTests`, `OpeningBalanceImportIntegrationTests` |
| Tenant isolation | `TenancyIntegrationTests` |
| Architecture | `ModularityTests` (Spring Modulith module boundaries), `ArchitectureTests` (ArchUnit layering) |
| API contract | `ResponseEnvelopeIntegrationTests`, `ApiDocsIntegrationTests` |

---

## 🚢 Deployment

### Docker image

```bash
docker build -t chama-backend backend/
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod -e JWT_SECRET=<32+ random bytes> \
  -e DB_URL=jdbc:postgresql://<host>:5432/chama_db -e DB_USERNAME=<user> -e DB_PASSWORD=<password> \
  -e MAIL_HOST=<smtp host> -e FRONTEND_BASE_URL=https://<frontend> \
  chama-backend
```

The [`Dockerfile`](backend/Dockerfile) is multi-stage (JDK build, JRE runtime), uses Spring Boot's layered JAR so code changes only replace the last layer, and runs as a non-root user.

### CI/CD (GitHub Actions)

[`backend-ci-cd.yml`](.github/workflows/backend-ci-cd.yml) runs when files under `backend/` change:

1. **Build and test** on pushes to `main` and `feat/**`, on pull requests to `main`, and on manual runs: JDK 25, `./mvnw verify` with Redis and RabbitMQ service containers, test reports uploaded as artifacts.
2. **Publish image** on pushes to `main` and `v*` tags: builds the Docker image and pushes it to `ghcr.io/<owner>/chama-backend`, tagged by branch, semantic version and commit SHA.

There is no automated deployment step yet. `infrastructure/kubernetes/` (Kustomize base and overlays) and `infrastructure/monitoring/` (Prometheus, Grafana) are placeholders.

---

## 📁 Project Structure

```
chama-management-system/
├── backend/                                Spring Boot application (the whole backend)
│   ├── Dockerfile
│   ├── pom.xml, mvnw, mvnw.cmd
│   └── src/
│       ├── main/java/ke/chama/
│       │   ├── ChamaApplication.java
│       │   ├── shared/                     Money, PhoneNumber, response envelope, security, tenancy
│       │   └── modules/                    auth, chama, member, contribution, account, penalty, loan,
│       │                                   expense, investment, transaction, report, audit,
│       │                                   dataimport, notification
│       ├── main/resources/
│       │   ├── application.yaml, application-prod.yaml
│       │   └── db/migration/               Flyway migrations V1–V20
│       └── test/java/ke/chama/             unit, integration, tenancy and architecture tests
├── infrastructure/
│   ├── docker/docker-compose.yml           PostgreSQL, Mailpit, RabbitMQ, Redis
│   ├── kubernetes/                         placeholders (base, overlays/local, overlays/production)
│   └── monitoring/                         placeholders (Prometheus, Grafana)
├── docs/
│   ├── architecture/                       Architecture Decision Records
│   ├── api/                                response format, Postman collection
│   └── frontend/                           frontend build guide, API reference, TypeScript types
└── .github/workflows/backend-ci-cd.yml     build, test, publish image
```

---

## 🗺️ Roadmap

**Done**

- [x] Foundations: shared kernel, module boundaries, response envelope, Swagger
- [x] Auth: JWT, refresh-token rotation, lockout, email verification, password recovery
- [x] Chamas, memberships, per-chama roles and permissions, audit log
- [x] Multi-tenancy with row-level security; licences and platform onboarding
- [x] Double-entry ledger
- [x] Contributions: periods, payments, arrears
- [x] Penalties: automatic late penalties, fines, payments, waivers
- [x] Loans: applications, guarantors, quorum approval, disbursement, repayments
- [x] Expenses with maker-checker approval
- [x] Investments with approval, returns and profitability
- [x] Opening-balance import from Excel
- [x] Savings products, member accounts and loan products
- [x] Reports and dashboards
- [x] CI: build, test and publish the Docker image

**Next**

- [ ] Web frontend (specification in [`docs/frontend/`](docs/frontend/))
- [ ] M-Pesa integration (Safaricom Daraja: STK push, C2B callbacks, B2C disbursement)
- [ ] SMS notifications (Africa's Talking)
- [ ] Dividends / profit distribution to members
- [ ] Member exit with final settlement
- [ ] PDF statements and exports
- [ ] CORS configuration for separately hosted frontends
- [ ] Kubernetes manifests and automated deployment
- [ ] Monitoring: Prometheus metrics and Grafana dashboards
- [ ] Load testing (k6)

---

## 🧠 Architecture Decision Records

In [`docs/architecture/`](docs/architecture/):

- [ADR-006](docs/architecture/ADR-006-modular-monolith-over-microservices.md): Modular monolith over microservices
- [ADR-007](docs/architecture/ADR-007-multi-tenancy-shared-database.md): Multi-tenancy in a shared database, with a licence per chama

---

## 👤 Author

**Caleb Muchiri**
Full-Stack Software Engineer · Nairobi, Kenya

[![LinkedIn](https://img.shields.io/badge/LinkedIn-Connect-0077B5?style=flat&logo=linkedin)](https://linkedin.com/in/caleb-muchiri-909ba6266)
[![GitHub](https://img.shields.io/badge/GitHub-Follow-181717?style=flat&logo=github)](https://github.com/CalebMUC)

---

## 📄 License

MIT License: see [LICENSE](LICENSE) for details.
