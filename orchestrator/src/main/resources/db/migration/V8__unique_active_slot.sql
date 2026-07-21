-- Booking Conflict Prevention: Partial Unique Index
--
-- Enforce that only ONE appointment can occupy a slot in active status.
-- This is the PRIMARY defence against double-booking race conditions.
--
-- We use a partial index (WHERE clause) to allow multiple CANCELLED/COMPLETED
-- appointments for the same slot (valid scenario: rebooking after cancellation).
--
-- Key design decision:
-- - Deterministic constraint at the DB level (UNIQUE index)
-- - Prevents race condition BEFORE it creates orphan records
-- - Complements the saga/DLQ compensation as SECONDARY defence
-- - Trade-off: PostgreSQL evaluates uniqueness only on matching rows (efficient)

CREATE UNIQUE INDEX idx_appointments_active_slot
    ON appointments (slot_id, tenant_id)
    WHERE status IN ('SCHEDULED', 'CONFIRMED');

COMMENT ON INDEX idx_appointments_active_slot IS
    'Partial unique index: enforces one active appointment per slot per tenant. ' ||
    'SCHEDULED and CONFIRMED statuses share the slot; CANCELLED/COMPLETED do not.';
