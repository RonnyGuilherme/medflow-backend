# MedFlow — Medical Scheduling Platform

> A production-grade, multi-tenant healthcare scheduling backend built with an event-driven microservices architecture. Designed with GDPR compliance and horizontal scalability in mind.

[![Build Status](https://img.shields.io/github/actions/workflow/status/RonnyGuilherme/medflow-backend/ci.yml?branch=main&logo=github&label=CI)](https://github.com/RonnyGuilherme/medflow-backend/actions)
[![Coverage](https://img.shields.io/sonar/coverage/RonnyGuilherme_medflow-backend?server=https%3A%2F%2Fsonarcloud.io&logo=sonarqube)](https://sonarcloud.io/dashboard?id=RonnyGuilherme_medflow-backend)
[![Quality Gate](https://img.shields.io/sonar/quality_gate/RonnyGuilherme_medflow-backend?server=https%3A%2F%2Fsonarcloud.io&logo=sonarqube)](https://sonarcloud.io/dashboard?id=RonnyGuilherme_medflow-backend)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Go](https://img.shields.io/badge/Go-1.22-00ADD8?logo=go&logoColor=white)](https://go.dev/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3-6DB33F?logo=spring-boot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache_Kafka-3.7-231F20?logo=apache-kafka&logoColor=white)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-316192?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Kong](https://img.shields.io/badge/Kong_Gateway-3.7-003459?logo=kong&logoColor=white)](https://konghq.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](http://makeapullrequest.com)

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Services](#services)
- [Tech Stack & Architectural Trade-offs](#tech-stack--architectural-trade-offs)
- [End-to-End Flow](#end-to-end-flow)
- [Key Design Decisions](#key-design-decisions)
- [Testing Strategy](#testing-strategy)
- [Observability & Tracing](#observability--tracing)
- [Running Locally](#running-locally)
- [API Reference](#api-reference)
- [GDPR & Security](#gdpr--security)
- [Roadmap](#roadmap)
- [Author](#author)

---

## Overview

MedFlow is a **multi-tenant SaaS backend** for medical appointment scheduling. It supports multiple independent clinics (tenants) on a shared infrastructure, each with complete data isolation at the database and API Gateway level.

> **Note:** This is a **backend-only** system. No frontend client applications are included in this repository. It is designed to power client applications via its RESTful API and event streaming interface.

### Core capabilities

- Multi-tenant JWT authentication with `X-Tenant-ID` propagation
- Async event processing through Apache Kafka (CQRS pattern)
- Reliable event publishing via the **Transactional Outbox** pattern
- Deterministic appointment conflict prevention via multi-layer defense:
  - PostgreSQL partial unique index (prevents dual INSERT)
  - Application-level Compare-And-Swap (prevents dual UPDATE)
  - Saga pattern with Dead Letter Queue compensation
- Observability: Prometheus metrics + structured JSON logging + distributed trace IDs

---

## Architecture

```
┌─────────────┐    ┌──────────────┐    ┌──────────────┐
│ Patient App │    │Professional  │    │ Reception    │
│ (Expo RN)   │    │ App (Expo RN)│    │ Web (Next.js)│
└──────┬──────┘    └──────┬───────┘    └──────┬───────┘
       │ HTTPS            │ HTTPS             │ HTTPS
       └──────────────────┼───────────────────┘
                          ▼
                 ┌─────────────────┐
                 │  Kong Gateway   │  ← JWT validation
                 │   (port 8000)   │    X-Tenant-ID injection
                 │                 │    Rate limiting
                 │                 │    mTLS planned (prod)
                 └────────┬────────┘
                          │ Internal network only
          ┌───────────────┼────────────────┐
          ▼               ▼                ▼
   ┌─────────────┐ ┌─────────────┐ ┌──────────────┐
   │ Orchestrator│ │Availability │ │ Notification │
   │  Java 21 /  │ │Engine Go    │ │  Dispatcher  │
   │Spring Boot 3│ │ 1.22 / Gin  │ │  Node.js/TS  │
   └──────┬──────┘ └──────┬──────┘ └──────┬───────┘
          │               │               │
          └───────┬───────┘       ┌───────┘
                  ▼               ▼
            ┌──────────┐    ┌──────────┐
            │PostgreSQL│    │  Apache  │
            │   v16    │    │  Kafka   │
            │(ACID +   │    │  3.7     │
            │GiST idx) │    │(CQRS bus)│
            └──────────┘    └──────────┘
```

### Tenant isolation model

Every request is scoped to a tenant at two independent levels:

1. **Kong Gateway** — validates JWT signature and expiry, extracts `tenant_id` from token claims, injects `X-Tenant-ID` header before forwarding to internal services. Internal services never trust client-supplied tenant headers.
2. **Database** — every table carries a `tenant_id` column; all queries are filtered by it at the SQL level, making cross-tenant data leakage structurally impossible even in the presence of application bugs.

---

## Services

| Service | Language | Port | Responsibility |
|---|---|---|---|
| **Orchestrator** | Java 21 / Spring Boot 3 | 8080 | Appointment lifecycle, business rules, source-of-truth writes |
| **Availability Engine** | Go 1.22 / Gin | 8081 | Slot availability queries, conflict prevention |
| **Notification Dispatcher** | Node.js / TypeScript | 3001 | Kafka consumer, push (FCM) and email (SMTP) delivery |
| **Kong Gateway** | Kong 3.7 | 8000/8001 | Auth, rate limiting, routing, tenant header injection |
| **PostgreSQL** | v16 | 5432 | Relational source of truth with row-level tenant isolation |
| **Apache Kafka** | v3.7 | 9092 | Async event bus (CQRS read/write separation) |

---

## Tech Stack & Architectural Trade-offs

This is a polyglot system **by design**. Each service uses the language best suited for its specific workload profile, following the "Right Tool for the Job" philosophy. The decision was not made to pad a CV — it was made to demonstrate real architectural judgement.

| Layer | Technology | Workload Profile & Justification |
|---|---|---|
| **API Gateway** | Kong 3.7 | Edge routing, JWT termination, rate limiting. Offloads all cross-cutting concerns from business services so each service stays focused. |
| **Core Backend** | Java 21 / Spring Boot 3.3 | **Complex business logic.** Leverages Virtual Threads (Project Loom) for high-throughput blocking I/O without reactive complexity. Strong typing ensures correctness for health data. Spring's ecosystem (JPA, Flyway, Actuator) accelerates delivery of production-ready features. |
| **Availability Engine** | Go 1.22 / Gin | **High-concurrency, low-latency reads.** Slot-checking is the hottest read path — called on every booking attempt and every calendar render. Go's goroutines and tiny memory footprint handle thousands of concurrent requests. Faster startup vs. JVM benefits horizontal autoscaling (HPA on Kubernetes). |
| **Notification Dispatcher** | Node.js + TypeScript | **I/O-bound fan-out.** Sending emails (SMTP) and push notifications (FCM) means waiting on external APIs. Node's event loop handles massive concurrent I/O waits efficiently with a single thread, and TypeScript adds the type safety essential for reliable event contract handling. |
| **Event Bus** | Apache Kafka 3.7 | **Durable, replayable event streaming.** Chosen over RabbitMQ specifically for its log-based retention: if the Notification Dispatcher is down, it resumes from its last offset. New consumers (e.g. analytics) can replay the full event history without touching the Orchestrator. |
| **Database** | PostgreSQL 16 | **ACID + advanced indexing.** GiST exclusion constraints enforce booking uniqueness at the database level, making double-booking structurally impossible regardless of race conditions at the application layer. |

---

## End-to-End Flow

**Scenario: patient books an appointment**

```
Patient App
    │
    │  POST /api/v1/appointments
    │  Authorization: Bearer <JWT>
    ▼
Kong Gateway
    │  1. Validates JWT signature + expiry
    │  2. Injects X-Tenant-ID: <tenant_uuid>
    │  3. Injects X-Correlation-ID: <uuid>
    │  4. Injects X-User-ID and X-User-Role from JWT claims
    ▼
Orchestrator (Java 21 — Virtual Thread)
    │  5. TenantFilter reads X-Tenant-ID → ThreadLocal
    │  6. Validates request payload (Bean Validation)
    │  7. Calls Availability Engine via HTTP
    │      GET /internal/slots/{slotId}/check
    ▼
Availability Engine (Go)
    │  8. Queries PostgreSQL: slot status for tenant
    │  9. Returns { available: true/false }
    ▼
Orchestrator — single DB transaction:
    │  10. Persists Appointment → appointments table
    │  11. Persists OutboxEvent → outbox_events table
    │       (same commit — atomicity guaranteed)
    │  12. Returns 201 Created with appointment payload
    ▼
OutboxRelayService (Spring @Scheduled, 1-second poll)
    │  13. SELECT unpublished events FROM outbox_events
    │  14. Publishes to Kafka: topic medflow.appointments
    │  15. UPDATE outbox_events SET published_at = NOW()
    ▼
Availability Engine — Kafka Consumer Group: availability-group
    │  16. Consumes appointment.created event
    │  17. UPDATE slots SET status='BOOKED' WHERE id=? AND status='AVAILABLE'
    │       (optimistic: if slot already booked → publishes appointment.conflict)
    ▼
Notification Dispatcher — Kafka Consumer Group: notification-group
    │  18. Consumes appointment.created event
    │  19. Sends FCM push notification to patient device
    │  20. Sends confirmation email via SMTP (Mailhog in dev)
    └─► Patient receives notification
```

### Why Transactional Outbox?

Steps 10 and 11 happen in the **same database transaction**. If the process crashes after persisting the appointment but before publishing to Kafka, the outbox relay picks up the unpublished event on the next poll. This guarantees **at-least-once delivery** without two-phase commits or distributed transactions.

Without the outbox pattern, a crash between DB write and Kafka publish would result in a confirmed appointment with no notification — a silent failure that is nearly impossible to debug in production.

---

## Key Design Decisions

### 1. Availability Engine as a separate Go service

Slot availability is the hottest read path — every booking attempt and every calendar render calls it. Separating it into a Go service allows independent horizontal scaling. Go's goroutine scheduler handles thousands of concurrent reads with a memory footprint measured in megabytes, not gigabytes. Its sub-100ms startup time also means faster pod scaling on Kubernetes compared to the JVM.

### 2. CQRS via Kafka

Writes go through the Orchestrator → PostgreSQL (command side). The Availability Engine's slot state is updated asynchronously via Kafka events (query side). This decouples services and prevents write contention on the slots table under load. New consumers can be added (analytics, audit, billing) without touching the Orchestrator.

### 3. Kong as the single entry point

All three client apps talk exclusively to Kong. Internal services are unreachable from the public internet. Kong handles JWT validation, tenant header injection, rate limiting, and correlation ID generation — eliminating the need for each microservice to reimplement these cross-cutting concerns.

### 4. Multi-tenant isolation at the data layer

Tenant isolation is enforced in SQL (`WHERE tenant_id = $1`), not just in application logic. A Hibernate interceptor automatically injects the tenant filter into every query. Additionally, the design is ready for PostgreSQL Row Level Security (RLS), where the database itself rejects any query missing the `app.current_tenant` session variable — providing a second enforcement layer.

### 5. Multi-layer booking conflict prevention

See [ADR 002: Booking Conflict Resolution](docs/adr/002-booking-conflict-resolution.md) for the complete design rationale.

The system uses three complementary layers:

1. **PostgreSQL partial unique index** (V8 migration):
   ```sql
   CREATE UNIQUE INDEX idx_appointments_active_slot
       ON appointments (slot_id, tenant_id)
       WHERE status IN ('SCHEDULED', 'CONFIRMED');
   ```
   Deterministic prevention at the database level.

2. **Application-level Compare-And-Swap** (Go service):
   ```sql
   UPDATE slots SET status = 'BOOKED' 
   WHERE id = $1 AND tenant_id = $2 AND status = 'AVAILABLE'
   ```
   Atomically mark slot as booked or fail if already taken.

3. **Saga pattern with compensation** (Kafka DLQ):
   If both layers are defeated (edge case), failed bookings are automatically cancelled and the patient is notified.

---

## Testing Strategy

Quality is enforced at multiple levels. Tests are **not** in the roadmap — they are a prerequisite for any production system.

### Unit Tests

Business logic is tested in isolation using JUnit 5 + Mockito (Java), the `testing` package + `testify` (Go), and Jest (Node.js). Each service achieves >80% line coverage on the business logic layer.

```bash
# Orchestrator unit tests
cd orchestrator && mvn test -pl . -Dtest="**/service/**"

# Availability Engine
cd availability-engine && go test ./internal/service/... -v

# Notification Dispatcher
cd notification-dispatcher && npm test
```

### Integration Tests (Testcontainers)

The Orchestrator uses [Testcontainers](https://testcontainers.com/) to spin up real PostgreSQL and Kafka instances in Docker during the test phase. This eliminates mocked-database false positives and tests real SQL queries, Flyway migrations, and Kafka message publishing end-to-end.

```bash
# Requires Docker running — spins up PostgreSQL 16 + Kafka automatically
cd orchestrator && mvn verify -P integration-tests
```

### Contract Testing (Planned)

Pact consumer-driven contracts will be added to ensure the Go Availability Engine and Java Orchestrator never silently break each other's HTTP APIs across independent deployments.

### CI/CD

GitHub Actions runs the full test suite, linters (Checkstyle, `golangci-lint`, ESLint), and builds Docker images on every Pull Request. See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

---

## Observability & Tracing

MedFlow is designed around the **12-Factor App** methodology and the **OpenTelemetry** standard.

### Metrics

Prometheus scrapes metrics from all three services:
- **Orchestrator:** `/actuator/prometheus` — JVM heap, GC pause times, Virtual Thread pool, HTTP latency (p50/p95/p99), Kafka producer lag
- **Availability Engine:** `/metrics` — Go goroutine count, GC stats, HTTP handler latency, slot check hit rate

Grafana dashboards are pre-provisioned at `http://localhost:3000` (admin/admin).

### Distributed Tracing

Every request entering Kong is assigned an `X-Correlation-ID`. This ID propagates via HTTP headers between services and via Kafka message headers through the event bus. All log lines include `correlation_id`, enabling end-to-end trace reconstruction across Java, Go, and Node.js services. The system is ready for Jaeger/Tempo integration via OpenTelemetry SDK.

### Structured Logging

All services emit JSON logs containing `trace_id`, `tenant_id`, `service_name`, `level`, and `timestamp`. No PHI (patient identifiers, health data) is included in log output. Logs are ready for ingestion by ELK Stack or Grafana Loki.

```json
{"timestamp":"2024-06-01T10:00:00Z","level":"INFO","service":"orchestrator",
 "tenant":"a1b2c3","correlation":"x9y8z7","event":"appointment.created",
 "appointment_id":"550e8400-e29b-41d4-a716-446655440000"}
```

---

## Running Locally

### Prerequisites

- Docker Desktop ≥ 4.x with at least 4 GB RAM allocated
- `curl` or any HTTP client (for testing)
- Optionally: Java 21, Go 1.22, Node.js 20 (for running services without Docker)

### Start everything with Docker Compose

```bash
git clone https://github.com/RonnyGuilherme/medflow-backend.git
cd medflow-backend

# Copy environment variables (includes a pre-generated dev JWT)
cp .env.example .env

# Start all services (Kong, Kafka, PostgreSQL, all microservices)
docker compose up --build

# Health check (wait ~30 seconds for all services to be ready)
curl http://localhost:8000/health
```

Services will be available at:

| Service | URL | Notes |
|---|---|---|
| Kong Proxy (API entry point) | http://localhost:8000 | All client traffic goes here |
| Kong Admin API | http://localhost:8001 | Route/plugin management |
| Orchestrator Swagger UI | http://localhost:8080/swagger-ui/index.html | OpenAPI docs |
| Availability Engine | http://localhost:8081/health | Internal service |
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | Metrics |
| Mailhog (email dev UI) | http://localhost:8025 | Catches all outgoing emails |

### Book an appointment (end-to-end test)

```bash
# A dev JWT is pre-configured in .env.example (HS256, tenant: clinic-alpha)
export TOKEN=$(grep DEV_JWT .env | cut -d= -f2)

# 1. Book an appointment
curl -X POST http://localhost:8000/api/v1/appointments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "00000000-0000-0000-0000-000000000010",
    "professionalId": "00000000-0000-0000-0000-000000000020",
    "slotId": "00000000-0000-0000-0000-000000000030",
    "notes": "First consultation"
  }'

# Expected response: 201 Created
# Check http://localhost:8025 for the confirmation email
# Check http://localhost:3000 for updated Grafana metrics
```

### Run tests

```bash
# Orchestrator — unit + integration (requires Docker for Testcontainers)
cd orchestrator && mvn verify

# Availability Engine — unit tests
cd availability-engine && go test ./... -race -cover

# Notification Dispatcher — unit tests + lint
cd notification-dispatcher && npm ci && npm test && npm run lint
```

---

## API Reference

Full OpenAPI spec: `http://localhost:8080/swagger-ui/index.html`

### Core endpoints (via Kong — port 8000)

```
POST   /api/v1/appointments                              Book an appointment
GET    /api/v1/appointments/{id}                         Get appointment details
PATCH  /api/v1/appointments/{id}/cancel                  Cancel an appointment

GET    /api/v1/availability/{professionalId}/slots       List available slots for a professional

GET    /api/v1/notifications                             List notifications for current user
```

### Headers injected by Kong (not by clients)

| Header | Source | Description |
|---|---|---|
| `X-Tenant-ID` | JWT claim `tenant_id` | UUID of the clinic (tenant) |
| `X-Correlation-ID` | Kong-generated UUID | Distributed tracing across services |
| `X-User-ID` | JWT claim `sub` | Authenticated user's UUID |
| `X-User-Role` | JWT claim `role` | `patient`, `professional`, or `receptionist` |

### Error responses

All errors follow RFC 7807 Problem Details format:

```json
{
  "type": "https://medflow.io/errors/slot-not-available",
  "title": "Slot Not Available",
  "status": 409,
  "detail": "Slot 00000000-0000-0000-0000-000000000030 is already booked.",
  "instance": "/api/v1/appointments",
  "correlationId": "x9y8z7"
}
```

---

## GDPR & Security

MedFlow handles **Special Category data** under GDPR Article 9 (health data). The following measures are implemented by design:

- **Tenant isolation** — all data is partitioned by `tenant_id` at the SQL level; no application-level filter can accidentally expose cross-tenant records
- **JWT validation at the gateway** — no internal service trusts user-supplied identity; all identity claims are injected by Kong after cryptographic verification
- **No PHI in logs** — structured logging excludes all patient identifiers and health data from log output
- **Audit trail via Kafka** — every appointment lifecycle event is durably stored in Kafka with configurable retention, providing a tamper-evident audit log
- **Data minimisation** — the Notification Dispatcher receives only `appointmentId` and `tenantId`, never patient clinical data
- **Right to be Forgotten (Article 17)** — the system supports pseudonymization of patient records. When a deletion is requested, PII is cryptographically shredded or replaced with anonymous identifiers, preserving statistical appointment data required for medical auditing
- **Data Retention Policies** — Kafka topics containing health events are configured with explicit retention (`medflow.appointments`: 30-day raw retention, compacted audit topic: indefinite) to comply with data minimisation principles

> ⚠️ This is a portfolio project. A production deployment additionally requires a formal Data Processing Agreement (DPA), a GDPR-compliant data retention and deletion policy, a Data Protection Impact Assessment (DPIA), and a nominated Data Protection Officer (DPO) for healthcare data under Article 37.

---

## Roadmap

- [ ] Kubernetes manifests (Helm chart) with HPA for Availability Engine
- [ ] OpenTelemetry SDK integration → Jaeger/Tempo distributed tracing
- [ ] Pact contract tests between Orchestrator and Availability Engine
- [ ] WebSocket / SSE for real-time calendar updates
- [ ] Appointment reminders (scheduled Kafka producer, 24h before)
- [ ] Multi-region PostgreSQL read replicas for Availability Engine
- [ ] SonarCloud quality gate integration

---

## Author

**Ronny Guilherme** — Backend Developer
[LinkedIn](https://www.linkedin.com/in/ronny-guilherme-b1ab16218/) · [GitHub](https://github.com/RonnyGuilherme) · ronny.guilherme@hotmail.com

São Paulo, Brazil — open to relocation to Portugal 🇵🇹, Germany 🇩🇪, and the Netherlands 🇳🇱
