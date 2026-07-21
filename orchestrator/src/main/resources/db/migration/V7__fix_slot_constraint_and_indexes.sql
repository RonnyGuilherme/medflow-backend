-- Two fixes in a single migration:
--
-- FIX 1: Slot exclusion constraint — BLOCKED slots now participate in overlap check.
--
--   Previous: WHERE (status != 'BLOCKED') — BLOCKED rows were invisible to the
--   exclusion index. A BLOCKED "lunch break" row could be inserted over an
--   existing AVAILABLE slot without any constraint violation, leaving the AVAILABLE
--   slot bookable despite the block.
--
--   Fix: Remove the WHERE predicate. ALL statuses now participate. To block a time
--   range, the caller must first DELETE or UPDATE overlapping AVAILABLE slots.
--   This is the correct domain invariant: a blocked time range must have no
--   competing entries.
--
-- FIX 2: Index column order for tenant_id selectivity.
--
--   Previous: (patient_id, tenant_id) — low selectivity when patient_id has many
--   distinct values. In a multi-tenant system, tenant_id always dramatically
--   narrows the result set and should be the leading column.
--
--   Fix: Reorder to (tenant_id, patient_id) and (tenant_id, professional_id).

-- ── FIX 1: Rebuild slot exclusion constraint ──────────────────────────────────
ALTER TABLE slots DROP CONSTRAINT IF EXISTS no_overlap;

ALTER TABLE slots
    ADD CONSTRAINT no_overlap EXCLUDE USING gist (
        professional_id  WITH =,
        tenant_id        WITH =,
        tstzrange(start_time, end_time) WITH &&
    );
-- No WHERE predicate: BLOCKED, AVAILABLE, and BOOKED all participate.
-- To mark a time range as blocked, remove overlapping AVAILABLE slots first.

COMMENT ON CONSTRAINT no_overlap ON slots IS
    'GiST exclusion constraint. All slot statuses participate (including BLOCKED). '
    'To create a BLOCKED time range, caller must first remove overlapping AVAILABLE/BOOKED rows.';

-- ── FIX 2: Rebuild indexes with tenant_id as leading column ──────────────────
DROP INDEX IF EXISTS idx_appt_patient;
DROP INDEX IF EXISTS idx_appt_professional;

-- tenant_id first → dramatically narrows result set before scanning patient_id
CREATE INDEX idx_appt_patient
    ON appointments (tenant_id, patient_id);

CREATE INDEX idx_appt_professional
    ON appointments (tenant_id, professional_id);
