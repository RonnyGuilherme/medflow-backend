CREATE TABLE slots (
    id               UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id        UUID         NOT NULL,
    professional_id  UUID         NOT NULL,
    start_time       TIMESTAMPTZ  NOT NULL,
    end_time         TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE'
                                  CHECK (status IN ('AVAILABLE', 'BOOKED', 'BLOCKED')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Enforces no double-booking at DB level via GiST range overlap detection.
    -- BLOCKED slots (e.g. lunch breaks) are excluded from the constraint.
    -- This fires even on concurrent INSERT — no application-level locking needed.
    CONSTRAINT no_overlap EXCLUDE USING gist (
        professional_id  WITH =,
        tenant_id        WITH =,
        tstzrange(start_time, end_time) WITH &&
    ) WHERE (status != 'BLOCKED')
);

CREATE INDEX idx_slots_tenant_prof ON slots (tenant_id, professional_id);
CREATE INDEX idx_slots_availability ON slots (tenant_id, professional_id, status, start_time);

COMMENT ON CONSTRAINT no_overlap ON slots
    IS 'GiST exclusion constraint prevents overlapping appointments for the same professional within a tenant.';
