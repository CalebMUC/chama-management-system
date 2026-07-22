<div align="center">

# Chama Management System

**A production-grade backend platform for managing Kenyan investment groups (Chamas)**

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3-6DB33F?style=flat&logo=spring-boot)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-316192?style=flat&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.13-FF6600?style=flat&logo=rabbitmq&logoColor=white)](https://www.rabbitmq.com/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat&logo=docker&logoColor=white)](https://www.docker.com/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-Ready-326CE5?style=flat&logo=kubernetes&logoColor=white)](https://kubernetes.io/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![CI](https://github.com/CalebMUC/chama-management-system/actions/workflows/ci.yml/badge.svg)](https://github.com/CalebMUC/chama-management-system/actions/workflows/ci.yml)

[Business Context](#-business-context) •
[Architecture](#-architecture) •
[Services](#-microservices) •
[Quick Start](#-quick-start) •
[API Reference](#-api-reference) •
[Roadmap](#-roadmap)

</div>

---

## 📋 Business Context

> **Chama** *(noun, Swahili)* — A community savings and investment group, common across Kenya and East Africa. Members pool money monthly, rotate lump-sum payouts, collectively invest, and offer each other loans backed by group savings.

There are an estimated **300,000+ registered Chamas in Kenya**, managing over **KES 500 billion** in assets. Most are still run on WhatsApp messages and paper ledgers, creating serious risks: missing records, disputes over balances, and no audit trail for regulatory compliance.

**This system solves that.**

### What a Chama Does (and What This System Manages)
Member joins Chama
│
▼
Pays monthly contribution (e.g. KES 5,000/month)
│
├──► Savings balance grows
│
├──► Eligible for loans (up to 3× savings balance)
│ │
│ ├──► Loan approved by committee
│ ├──► Disbursed via M-Pesa
│ └──► Repaid with interest → grows Chama fund
│
├──► Late contributions trigger automatic penalties
│
└──► Group invests surplus in Money Market Funds
│
└──► Monthly returns distributed proportionally

### Core Business Rules

| Rule | Detail |
|------|--------|
| **Loan eligibility** | Member must have ≥ 3 months contributions. Loan max = 3× personal savings balance |
| **Loan approval** | Requires committee quorum (configurable, default: 2 of 3 officers) |
| **Late contribution penalty** | 2% of monthly contribution amount per month late |
| **Loan interest** | 10% flat rate per agreed term (common Chama standard) |
| **Profit distribution** | Investment returns split proportionally to each member's savings balance at month-end |
| **Guarantors** | Loans above KES 50,000 require 2 guarantors from active members |

---

## 🏗️ Architecture

This system is built as a **microservices architecture** following Domain-Driven Design (DDD), Clean Architecture, and Event-Driven Architecture principles.

### Why microservices for a Chama system?

- **Independent deployability** — the M-Pesa payment adapter can be updated without touching loan logic
- **Fault isolation** — a reporting service outage does not affect contribution recording
- **Team scalability** — each bounded context (loans, contributions, penalties) can be owned by a separate team
- **Technology fit** — financial systems benefit from strict separation of concerns and explicit audit trails

### High-Level System Design
┌─────────────────────────────────────────────────────────┐
│ CLIENT TIER │
│ Web App · Mobile App · Admin Dashboard │
└───────────────────────┬─────────────────────────────────┘
│ HTTPS
┌───────────────────────▼─────────────────────────────────┐
│ API GATEWAY (Spring Cloud Gateway) │
│ JWT Validation · Rate Limiting · Routing │
└──┬──────┬──────┬──────┬──────┬──────┬──────┬───────────┘
│ │ │ │ │ │ │
▼ ▼ ▼ ▼ ▼ ▼ ▼
[Auth] [Member] [Contrib] [Loan] [Penalty] [Invest] [Report]
│ │ │ │ │ │ │
└──────┴──────┴──┬───┴──────┴──────┴──────┘
│
┌───────────▼──────────────┐
│ RabbitMQ Event Bus │
│ chama.events (topic) │
│ · member.created │
│ · contribution.received│
│ · loan.approved │
│ · penalty.created │
└───────────┬──────────────┘
│
┌───────────▼──────────────┐
│ [Notification] [Txn] │
│ [Reporting] [M-Pesa] │
└──────────────────────────┘
### Architecture Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Communication** | Sync (OpenFeign) + Async (RabbitMQ) | Feign for real-time queries, RabbitMQ for decoupled events |
| **Database strategy** | One PostgreSQL schema per service | True bounded context isolation; no cross-service JOINs |
| **Auth** | Stateless JWT (15min access + 7-day refresh) | Scales horizontally; no session store needed |
| **Money storage** | `BIGINT` (KES cents) | Avoids floating-point precision issues in financial calculations |
| **Primary keys** | `UUID v4` | Prevents ID enumeration attacks; works across distributed services |
| **API versioning** | URI versioning (`/api/v1/`) | Explicit, easy to route at gateway level |

---

## 🔧 Microservices

| Service | Port | Responsibility | Key Tech |
|---------|------|---------------|----------|
| `api-gateway` | 8080 | Single entry point, JWT validation, routing | Spring Cloud Gateway, Eureka |
| `auth-service` | 8081 | Login, registration, JWT issuance & refresh | Spring Security 6, JJWT |
| `member-service` | 8082 | Member onboarding, profiles, RBAC, approval | Spring Data JPA, Flyway |
| `contribution-service` | 8083 | Monthly contributions, savings balances, cycles | Spring Data JPA, @Scheduled |
| `loan-service` | 8084 | Loan applications, approval, disbursement, repayment | Spring Data JPA, OpenFeign |
| `penalty-service` | 8085 | Auto-penalties, waivers, penalty reports | RabbitMQ consumer |
| `investment-service` | 8086 | MMF deposits, monthly returns, profit sharing | Spring Data JPA |
| `transaction-service` | 8087 | Double-entry ledger, audit trail, reversals | RabbitMQ consumer |
| `notification-service` | 8088 | SMS (Africa's Talking), email, push notifications | RabbitMQ consumer |
| `reporting-service` | 8089 | Member statements, PDFs, financial summaries | iText/PDFBox, CQRS read side |
| `mpesa-adapter` | 8090 | M-Pesa STK push, C2B callbacks, B2C payouts | Safaricom Daraja API |

### Service Interaction Map

POST /contributions ──► contribution-service
│
├──► [event] ContributionReceived
│ ├──► transaction-service (records ledger entry)
│ └──► notification-service (sends SMS receipt)
│
└──► [event] ContributionMissed (if month-end missed)
├──► penalty-service (creates penalty record)
└──► notification-service (sends reminder SMS)

POST /loans/apply ──► loan-service
│
├──► [Feign] member-service (verify eligibility)
├──► [Feign] contribution-service (check savings balance)
│
├──► [event] LoanApproved
│ └──► notification-service (approval SMS)
│
└──► [event] LoanDisbursed
├──► mpesa-adapter (B2C payout)
├──► transaction-service (ledger entry)
└──► notification-service (disbursement SMS)

---

## 🚀 Quick Start

### Prerequisites

- Java 21+
- Docker & Docker Compose
- Maven 3.9+

### Run the full stack locally

```bash
git clone https://github.com/CalebMUC/chama-management-system.git
cd chama-management-system

# Start all infrastructure (PostgreSQL, RabbitMQ, Redis, Zipkin)
docker compose -f infrastructure/docker/docker-compose.yml up -d

# Start all services (from repo root)
./scripts/start-all.sh

# Or start individual service
cd services/member-service
mvn spring-boot:run
```

### Verify everything is running

| URL | Service |
|-----|---------|
| http://localhost:8080 | API Gateway |
| http://localhost:15672 | RabbitMQ Management (guest/guest) |
| http://localhost:3000 | Grafana Dashboards (admin/admin) |
| http://localhost:9090 | Prometheus |
| http://localhost:9411 | Zipkin Tracing |

### Walk through the core business flow

```bash
# 1. Register a member
curl -X POST http://localhost:8080/api/v1/members \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Jane",
    "lastName": "Wanjiku",
    "email": "jane.wanjiku@email.com",
    "phoneNumber": "+254712345678",
    "nationalId": "12345678"
  }'

# 2. Login and get JWT
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"jane.wanjiku@email.com","password":"Password123!"}' \
  | jq -r '.accessToken')

# 3. Record monthly contribution
curl -X POST http://localhost:8080/api/v1/contributions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "memberId": "...",
    "amount": 500000,
    "period": "2025-01",
    "paymentMethod": "MPESA",
    "mpesaReference": "QGH7K9XYZ"
  }'

# 4. Apply for a loan
curl -X POST http://localhost:8080/api/v1/loans/apply \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "memberId": "...",
    "amount": 1500000,
    "termMonths": 6,
    "purpose": "Business expansion"
  }'
```

---

## 📡 API Reference

Full OpenAPI 3.0 specifications are in [`docs/api/openapi/`](docs/api/openapi/).

### Authentication

POST /api/v1/auth/register Register new user account
POST /api/v1/auth/login Login, returns JWT pair
POST /api/v1/auth/refresh Refresh access token
POST /api/v1/auth/logout Invalidate refresh token

### Member Management

POST /api/v1/members Register new Chama member
GET /api/v1/members List all members (paginated)
GET /api/v1/members/{id} Get member profile
PUT /api/v1/members/{id} Update member profile
PATCH /api/v1/members/{id}/approve Approve pending member (CHAIRMAN only)
PATCH /api/v1/members/{id}/suspend Suspend active member
GET /api/v1/members/{id}/summary Member financial summary

### Contributions

POST /api/v1/contributions Record contribution payment
GET /api/v1/contributions List contributions (filterable)
GET /api/v1/contributions/member/{id} Member contribution history
GET /api/v1/contributions/cycles List contribution cycles
GET /api/v1/contributions/summary Group contribution summary

### Loans

POST /api/v1/loans/apply Submit loan application
GET /api/v1/loans List all loans (paginated)
GET /api/v1/loans/{id} Loan details
PATCH /api/v1/loans/{id}/approve Approve loan (TREASURER only)
PATCH /api/v1/loans/{id}/reject Reject application
POST /api/v1/loans/{id}/disburse Trigger M-Pesa disbursement
POST /api/v1/loans/{id}/repayments Record repayment
GET /api/v1/loans/{id}/statement Loan statement PDF
GET /api/v1/loans/member/{memberId} Member's loan history

### Penalties

GET /api/v1/penalties List all penalties
GET /api/v1/penalties/member/{id} Member's penalties
PATCH /api/v1/penalties/{id}/waive Waive penalty (CHAIRMAN only)
GET /api/v1/penalties/report Penalty summary report

### Reports

GET /api/v1/reports/member/{id}/statement Member statement (PDF)
GET /api/v1/reports/financial-summary Group financial overview
GET /api/v1/reports/contributions Contribution report
GET /api/v1/reports/loans Loan portfolio report

### Common Query Parameters

?page=0&size=20&sort=createdAt,desc Pagination & sorting
?startDate=2025-01-01&endDate=2025-12-31 Date range filtering
?status=ACTIVE Status filtering
?search=wanjiku Full-text search

---

## 🔐 Security Model

ROLE_MEMBER → View own profile, contributions, loans, statements
ROLE_TREASURER → All MEMBER permissions + approve/reject loans, record contributions
ROLE_SECRETARY → All MEMBER permissions + manage member records, generate reports
ROLE_CHAIRMAN → All permissions + approve members, waive penalties, override decisions
ROLE_ADMIN → System administration, user management, configuration

JWT access tokens expire in **15 minutes**. Refresh tokens expire in **7 days** and are stored server-side (Redis) to support revocation on logout.

---

## 📊 Observability

### Metrics (Prometheus + Grafana)

Business metrics exposed on `/actuator/prometheus`:

chama_contributions_received_total{currency="KES"}
chama_loans_disbursed_total{currency="KES"}
chama_penalties_created_total
chama_active_members_count
chama_loan_approval_duration_seconds    

### Distributed Tracing (Zipkin)

Every request carries a `X-B3-TraceId` header through the full service chain. A single "apply for loan" request can be traced across gateway → loan-service → member-service → contribution-service → notification-service.

---

## 🗃️ Database Design

Each service owns its own PostgreSQL schema. No cross-service foreign keys. Cross-service data needs are satisfied by events or Feign calls.

chama_auth → users, roles, permissions, refresh_tokens
chama_members → members, member_profiles, kyc_documents, approval_workflows
chama_contributions → contributions, savings_accounts, contribution_cycles
chama_loans → loan_applications, loans, loan_guarantors, repayment_schedules
chama_penalties → penalties, penalty_rules, penalty_accounts
chama_investments → investment_accounts, mmf_deposits, monthly_returns, profit_shares
chama_transactions → transactions, ledger_entries, audit_log

---

## ⚡ Event-Driven Architecture

All inter-service communication that doesn't require a synchronous response uses RabbitMQ.

**Exchange:** `chama.events` (topic exchange)

| Routing Key | Event | Publisher | Consumers |
|-------------|-------|-----------|-----------|
| `member.created` | MemberCreated | member-service | notification, auth |
| `member.approved` | MemberApproved | member-service | notification, transaction |
| `contribution.received` | ContributionReceived | contribution-service | transaction, notification |
| `contribution.missed` | ContributionMissed | contribution-service | penalty, notification |
| `loan.approved` | LoanApproved | loan-service | notification, transaction |
| `loan.disbursed` | LoanDisbursed | loan-service | mpesa-adapter, transaction, notification |
| `loan.repayment.recorded` | LoanRepaymentRecorded | loan-service | transaction, notification |
| `penalty.created` | PenaltyCreated | penalty-service | transaction, notification |
| `investment.profit.distributed` | ProfitDistributed | investment-service | transaction, notification |

Failed message processing routes to `chama.dlq` after 3 retries with exponential backoff.

---

## 🧪 Testing Strategy

Unit tests → Domain logic, service layer (JUnit 5, Mockito)
Integration tests → Full service stack with real DB (TestContainers + PostgreSQL)
API tests → HTTP layer with MockMvc
Contract tests → Service-to-service API contracts (Spring Cloud Contract)
E2E tests → Critical business flows (contribution → loan → repayment)

```bash
# Run all tests
mvn test

# Run with TestContainers (requires Docker)
mvn verify -P integration-tests

# Coverage report (target: >80%)
mvn jacoco:report
```

---

## 🚢 Deployment

### Docker Compose (local development)

```bash
docker compose -f infrastructure/docker/docker-compose.yml up
```

### Kubernetes (production-like)

```bash
# Apply base manifests
kubectl apply -k infrastructure/kubernetes/overlays/local

# Check status
kubectl get pods -n chama-system

# Access via ingress
curl http://chama.local/api/v1/members
```

### CI/CD Pipeline (GitHub Actions)

Every push to `main`:
1. Run unit + integration tests
2. Build Docker images (multi-stage, ~180MB)
3. Push to GitHub Container Registry (GHCR)
4. Deploy to Kubernetes (rolling update, zero downtime)
5. Run smoke tests against deployed environment

---

## 📁 Project Structure

chama-management-system/
├── services/ # One folder per microservice
│ ├── auth-service/
│ ├── member-service/
│ ├── contribution-service/
│ ├── loan-service/
│ ├── penalty-service/
│ ├── investment-service/
│ ├── transaction-service/
│ ├── notification-service/
│ ├── reporting-service/
│ ├── mpesa-adapter/
│ └── api-gateway/
├── infrastructure/
│ ├── docker/ # docker-compose.yml
│ ├── kubernetes/ # K8s manifests (Kustomize)
│ └── monitoring/ # Prometheus + Grafana config
├── docs/
│ ├── architecture/ # Architecture Decision Records (ADRs)
│ ├── api/openapi/ # OpenAPI 3.0 specs
│ └── runbooks/ # Operational runbooks
└── .github/workflows/ # CI/CD pipelines

---

## 🗺️ Roadmap

- [x] Repository structure and documentation
- [ ] Auth Service — JWT authentication
- [ ] Member Service — onboarding & approval workflow
- [ ] Contribution Service — monthly cycles & savings
- [ ] Loan Service — application, approval & disbursement
- [ ] Penalty Service — automated penalty engine
- [ ] Investment Service — MMF tracking & profit sharing
- [ ] Transaction Service — double-entry ledger
- [ ] Notification Service — SMS & email via Africa's Talking
- [ ] Reporting Service — statements & PDF generation
- [ ] M-Pesa Adapter — Safaricom Daraja integration
- [ ] API Gateway — routing, rate limiting, auth filter
- [ ] Kubernetes manifests & Helm charts
- [ ] Monitoring dashboards (Prometheus + Grafana)
- [ ] CI/CD pipeline (GitHub Actions)
- [ ] Load testing (k6)

---

## 🧠 Architecture Decision Records

Key decisions documented in [`docs/architecture/`](docs/architecture/):

- [ADR-001](docs/architecture/ADR-001-database-per-service.md) — Database-per-service over shared schema
- [ADR-002](docs/architecture/ADR-002-rabbitmq-over-kafka.md) — RabbitMQ over Kafka for this scale
- [ADR-003](docs/architecture/ADR-003-jwt-stateless-auth.md) — Stateless JWT with Redis-backed refresh
- [ADR-004](docs/architecture/ADR-004-money-as-bigint.md) — Storing money as BIGINT (KES cents)
- [ADR-005](docs/architecture/ADR-005-uuid-primary-keys.md) — UUID primary keys over auto-increment

---

## 👤 Author

**Caleb Muchiri**
Full-Stack Software Engineer · Nairobi, Kenya

[![LinkedIn](https://img.shields.io/badge/LinkedIn-Connect-0077B5?style=flat&logo=linkedin)](https://linkedin.com/in/caleb-muchiri-909ba6266)
[![GitHub](https://img.shields.io/badge/GitHub-Follow-181717?style=flat&logo=github)](https://github.com/CalebMUC)

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.
