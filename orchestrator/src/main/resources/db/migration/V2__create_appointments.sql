CREATE TABLE appointments (
    id               UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id        UUID         NOT NULL,
    patient_id       UUID         NOT NULL,
    professional_id  UUID         NOT NULL,
    slot_id          UUID         NOT NULL,
    status           VARCHAR(20)  NOT NULL
                                  CHECK (status IN ('SCHEDULED','CONFIRMED','CANCELLED','COMPLETED','NO_SHOW')),
    notes            TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Multi-column indexes — all scoped to tenant_id for data isolation
CREATE INDEX idx_appt_tenant       ON appointments (tenant_id);
CREATE INDEX idx_appt_patient      ON appointments (patient_id,      tenant_id);
CREATE INDEX idx_appt_professional ON appointments (professional_id, tenant_id);
CREATE INDEX idx_appt_status       ON appointments (status,          tenant_id);
CREATE INDEX idx_appt_slot         ON appointments (slot_id,         tenant_id);

COMMENT ON TABLE  appointments              IS 'Source of truth for all appointment records. tenant_id enforces row-level isolation.';
COMMENT ON COLUMN appointments.notes        IS 'Clinical notes. Not propagated to event payloads (data minimisation, GDPR Art.5).';
COMMENT ON COLUMN appointments.tenant_id    IS 'Clinic owning this record. All queries must filter by this column.';
