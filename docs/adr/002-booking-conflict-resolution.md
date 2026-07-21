# ADR 002 — Booking Conflict Resolution: Deterministic Prevention + Saga Compensation

**Status:** Accepted
**Date:** 2026-07-20
**Author:** Engineering Team

## Problem

The system exhibited a critical race condition in appointment booking that violated the invariant:
*"only one patient appointment may occupy a given slot at a time."*

**Root Cause:**
1. Orchestrator performs optimistic check (Go service returns AVAILABLE)
2. Two threads insert appointments for the same slot simultaneously
3. Availability Engine processes events asynchronously, attempting `UPDATE slots SET status = BOOKED WHERE status = AVAILABLE`
4. First update succeeds; second fails silently and was discarded
5. Both appointments remain SCHEDULED in the database — permanent inconsistency

**Why It Happened:**
- `HandleAppointmentCreated()` returned `(bool, nil)` on conflict, not an error
- Consumer ignored the boolean and only checked `err`
- No distinction between transient (DB timeout) and deterministic (slot taken) failures
- No compensation mechanism for lost bookings

---

## Alternatives Considered

### 1. **Pessimistic Lock (SELECT FOR UPDATE)**
```sql
BEGIN;
  SELECT * FROM slots WHERE id = ? AND tenant_id = ? FOR UPDATE;
  UPDATE slots SET status = 'BOOKED' WHERE id = ? AND status = 'AVAILABLE';
  INSERT INTO appointments (...);
COMMIT;
```

**Pros:**
- Strongest consistency guarantee — serialized execution
- No race window at all

**Cons:**
- Synchronous lock on Kafka event processing — blocks consumer thread
- Deadlock risk under concurrent load
- Availability Engine become bottleneck (Go service on single DB connection)
- Violates async event-driven architecture (Kafka exists to decouple systems)

**Decision:** Rejected — incompatible with async saga pattern

---

### 2. **Partial Unique Index Only**
```sql
CREATE UNIQUE INDEX idx_appointments_active_slot 
  ON appointments (slot_id, tenant_id) 
  WHERE status IN ('SCHEDULED', 'CONFIRMED');
```

**Pros:**
- Deterministic prevention at database level
- Immediate feedback to Orchestrator (409 Conflict on second insert)
- No message processing, no DLQ, no retry loop

**Cons:**
- Only prevents writes to Orchestrator table
- Doesn't prevent Go service from marking slot BOOKED twice (eventual consistency gap)
- Doesn't explain to patient why appointment was rejected (no compensation event)
- Doesn't survive Availability Engine outage (slot still marked BOOKED but appointment missing)

**Decision:** Necessary but insufficient — must be combined with saga

---

### 3. **Saga + Compensation Only (Our First Implementation)**
- No unique index in appointments table
- Rely entirely on Go service's CAS (Compare-And-Swap) via `MarkBooked()`
- On conflict, route to DLQ and auto-cancel
- Asymmetric response time: immediate success for first patient, delayed cancellation for second

**Pros:**
- Async event-driven architecture intact
- Graceful degradation if unique index fails

**Cons:**
- Patient experience: "Your appointment was confirmed" → wait 3+ seconds → "Actually, it was cancelled"
- Wasted DLQ processing for deterministic failures
- Logs polluted with unnecessary retries
- Database still contains orphan records (temporarily)

**Decision:** Primary mechanism, but needs optimization

---

### 4. **Hybrid: Unique Index + Saga + Error Differentiation (CHOSEN)**

Combine the three layers:

1. **Deterministic Prevention (Strongest):** Partial unique index in PostgreSQL
2. **Application-Level CAS (Medium):** Go service MarkBooked() with conflict detection
3. **Compensation Saga (Weakest):** Auto-cancel appointments stuck in SCHEDULED + notify patient

**Flow:**
```
Patient A → checkSlot (AVAILABLE) ✓
Patient B → checkSlot (AVAILABLE) ✓

Patient A → INSERT appointments SCHEDULED (succeeds)
Patient B → INSERT appointments SCHEDULED (succeeds) — unique index not violated yet (both have same slot_id)

Event processing:
A → MarkBooked() → UPDATE slots SET status='BOOKED' WHERE status='AVAILABLE' ✓
B → MarkBooked() → UPDATE slots SET status='BOOKED' WHERE status='AVAILABLE' ✗ (conflict)
    → ErrSlotAlreadyBooked (deterministic error, no retry)
    → Route to DLQ immediately (0 retries)
    → BookingCompensationListener cancels appointment B
    → Patient B notified: "Slot was taken by another patient, your appointment cancelled"

**Future (post-migration):**
With unique index enforced, Patient B's INSERT fails at step 1:
    → Orchestrator catches DataIntegrityViolationException
    → Returns 409 Conflict immediately
    → Patient B gets feedback before confirmation
    → Zero DLQ events, zero async cancellation, zero confusion
```

---

## Decision

Implement all three layers:

### Layer 1: Deterministic Prevention
- Migration: `V8__unique_active_slot.sql`
- Partial unique index on `appointments(slot_id, tenant_id)` WHERE `status IN ('SCHEDULED', 'CONFIRMED')`
- Prevents multiple active appointments per slot at database level
- Allows rebooking scenarios (CANCELLED/COMPLETED appointments don't occupy slot)

### Layer 2: Application-Level CAS
- File: `availability-engine/internal/service/slot_service.go`
- `HandleAppointmentCreated()` now returns `error` instead of `(bool, error)`
- Explicit `ErrSlotAlreadyBooked` when conflict detected
- Clear typing: deterministic failure, not ambiguous boolean

### Layer 3: Compensation Saga
- Consumer: Differentiate `ErrSlotAlreadyBooked` (deterministic) from transient errors
- Deterministic → DLQ immediately (0 retries), no throughput waste
- Transient errors → Retry with exponential backoff (100ms → 1600ms, 5 attempts max)
- File: `availability-engine/internal/consumer/appointment_consumer.go` method `processWithRetry()`
- DLQ listener: `orchestrator/service/BookingCompensationListener.java`
- Auto-cancels stuck appointments and publishes notification event

---

## Consequences

### Benefits
1. **Multiple defenses:** Even if one layer fails, others catch the error
2. **Clear error semantics:** Deterministic conflicts don't waste retry cycles
3. **Better patient experience:** 
   - With unique index: immediate 409 response
   - Without index: async cancellation + notification
4. **Auditability:** DLQ captures why compensation was triggered
5. **Monitoring:** DLQ message rate indicates booking contention
6. **Backwards-compatible:** Works with existing Availability Engine until unique index deployed

### Trade-offs
1. **Complexity:** Three layers instead of one
   - Worth it: separates concerns (prevention vs. compensation)
   - Enables testing each layer independently
2. **Patient experience temporarily degrades** until unique index is enforced
   - Delay: ~100ms (first retry backoff) before cancellation
   - Acceptable: Better than permanent orphan state
3. **Double-write logic:** Both Orchestrator and Go service check uniqueness
   - Not redundant: provides defense-in-depth
   - Orchestrator's index catches fast path
   - Go service's CAS catches slow path

---

## Implementation Checklist

- [x] Create `V8__unique_active_slot.sql` migration
- [x] Implement `ErrSlotAlreadyBooked` in slot_service.go
- [x] Differentiate transient vs deterministic errors in consumer
- [x] Implement `BookingCompensationListener` in Orchestrator
- [x] Add DLQ topic configuration (env var `KAFKA_DLQ_TOPIC`)
- [x] Update unit tests for new error types
- [ ] Update integration tests with concurrent booking scenarios
- [ ] Update deployment documentation (new env var)
- [ ] Monitor DLQ message rate in production

---

## Testing Strategy

### Unit Tests
- ✅ `TestHandleAppointmentCreated_SlotAlreadyBooked_ReturnsError`
- ✅ `TestHandleAppointmentCreated_MarksSlotBooked`
- Verify `errors.Is(err, ErrSlotAlreadyBooked)` works correctly

### Integration Tests
- Concurrent requests to same slot: verify unique index prevents orphans
- Go service MarkBooked race: verify ErrSlotAlreadyBooked caught
- DLQ consumption: verify compensation listener cancels stuck appointments

### Load Test (k6)
- 100 concurrent users booking same slot
- Verify: only 1 succeeds, 99 are compensated
- Verify: no orphaned SCHEDULED appointments
- Verify: notification events published for all 99 cancellations

---

## Migration Path (Future Enhancement)

Once unique index is deployed and working, can simplify to:
1. Remove Go service's `MarkBooked()` logic (rely on index)
2. Keep consumer differentiation (fast-fail on constraint violations)
3. Keep DLQ compensation (defensive programming)

Result: All conflicts caught at database insertion time, zero DLQ traffic under normal load.
