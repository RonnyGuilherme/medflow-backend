# ADR 001 — Availability Engine Shares the Orchestrator's PostgreSQL Schema

**Status:** Accepted (with known trade-offs, migration path documented)
**Date:** 2024-06-01

## Context

The Availability Engine (Go) reads and writes the `slots` table, which is managed by
Flyway migrations in the Orchestrator's (`orchestrator/src/main/resources/db/migration`)
codebase. This means two separate services — with different languages, deployments, and
ownership — share direct access to the same database schema.

## Decision

For the current scope, the Availability Engine reads and writes `slots` directly in the
shared PostgreSQL database. This is an explicit trade-off, not an oversight.

## Consequences

**Benefits:**
- Zero latency for slot queries — no network hop to the Orchestrator on every calendar render
- Simpler consistency model for the read path — the Go service reads the same rows that
  Kafka events update
- Avoids a synchronous request chain for the high-throughput availability check path

**Drawbacks:**
- **Schema coupling** — changes to the `slots` table require coordinated releases between
  the Orchestrator (Flyway migrations) and the Availability Engine (Go queries)
- **Write coupling** — the Go service's `UPDATE slots SET status = 'BOOKED'` runs in the
  same database as the Orchestrator's `INSERT INTO appointments`. Under extreme write load,
  lock contention is possible
- **Bounded context violation** — `slots` logically belongs to the Availability Engine's
  domain but is physically owned by the Orchestrator's migration set

## Migration Path (when scaling warrants it)

1. **Short term:** Move `V4__create_slots.sql` to a separate `availability-engine/migrations/`
   directory managed by Go's `golang-migrate/migrate`. This gives the Availability Engine
   schema ownership without changing the shared DB.

2. **Medium term:** Extract a `ScheduleService` (Saga orchestrator) that owns `slots` and
   exposes a `POST /internal/slots/:id/reserve` command. The Availability Engine becomes a
   pure read replica, consuming slot state from Kafka events only.

3. **Long term:** Separate databases per service with event sourcing for cross-service
   consistency (the CQRS model this project already approximates).

## Current Mitigation

- All Availability Engine writes to `slots` use optimistic `UPDATE ... WHERE status = 'AVAILABLE'`
  — no pessimistic locks
- The PostgreSQL GiST exclusion constraint on `slots` prevents double-booking regardless
  of which service issues the write
- The Orchestrator never writes to `slots` directly — all slot state changes flow through
  Kafka events consumed by the Availability Engine

See also: [docs/deployment.md](../deployment.md) for production hardening notes.
