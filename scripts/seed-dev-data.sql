-- ────────────────────────────────────────────────────────────────────────────
-- MedFlow Development Seed Data
-- Pre-populates two demo tenants with professionals and available slots.
-- JWT in .env.example is pre-configured for tenant clinic-alpha.
-- ────────────────────────────────────────────────────────────────────────────

-- Extensions (idempotent)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- ── Tenants (clinics) ────────────────────────────────────────────────────────
-- (Tenants are managed via identity service; UUIDs are referenced here)
-- tenant: 00000000-0000-0000-0000-000000000001 → Clinic Alpha (matches dev JWT)
-- tenant: 00000000-0000-0000-0000-000000000002 → Clinic Beta

-- ── Slots — Clinic Alpha ─────────────────────────────────────────────────────
-- Professional: 00000000-0000-0000-0000-000000000020 (Dr. Silva)

DO $$
DECLARE
  tenant_a UUID := '00000000-0000-0000-0000-000000000001';
  tenant_b UUID := '00000000-0000-0000-0000-000000000002';
  dr_silva UUID := '00000000-0000-0000-0000-000000000020';
  dr_costa UUID := '00000000-0000-0000-0000-000000000021';
  slot_1   UUID := '00000000-0000-0000-0000-000000000030';
  slot_2   UUID := '00000000-0000-0000-0000-000000000031';
  slot_3   UUID := '00000000-0000-0000-0000-000000000032';
BEGIN
  -- Insert slots (only if table exists and is empty for idempotency)
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'slots') THEN

    INSERT INTO slots (id, tenant_id, professional_id, start_time, end_time, status)
    VALUES
      -- Clinic Alpha — Dr. Silva — slots for the next 7 days
      (slot_1,                                          tenant_a, dr_silva,
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '09:00',
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '09:30', 'AVAILABLE'),

      (slot_2,                                          tenant_a, dr_silva,
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '09:30',
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '10:00', 'AVAILABLE'),

      (slot_3,                                          tenant_a, dr_silva,
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '10:00',
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '10:30', 'AVAILABLE'),

      (uuid_generate_v4(),                              tenant_a, dr_silva,
       (CURRENT_DATE + 2)::timestamp AT TIME ZONE 'UTC' + INTERVAL '14:00',
       (CURRENT_DATE + 2)::timestamp AT TIME ZONE 'UTC' + INTERVAL '14:30', 'AVAILABLE'),

      (uuid_generate_v4(),                              tenant_a, dr_silva,
       (CURRENT_DATE + 3)::timestamp AT TIME ZONE 'UTC' + INTERVAL '11:00',
       (CURRENT_DATE + 3)::timestamp AT TIME ZONE 'UTC' + INTERVAL '11:30', 'AVAILABLE'),

      -- Lunch break — BLOCKED (excluded from GiST constraint)
      (uuid_generate_v4(),                              tenant_a, dr_silva,
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '12:00',
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '13:00', 'BLOCKED'),

      -- Clinic Beta — Dr. Costa
      (uuid_generate_v4(),                              tenant_b, dr_costa,
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '08:00',
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '08:30', 'AVAILABLE'),

      (uuid_generate_v4(),                              tenant_b, dr_costa,
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '08:30',
       (CURRENT_DATE + 1)::timestamp AT TIME ZONE 'UTC' + INTERVAL '09:00', 'AVAILABLE')

    ON CONFLICT (id) DO NOTHING;

    RAISE NOTICE 'Seed: % slots inserted for dev', (SELECT COUNT(*) FROM slots);
  END IF;
END;
$$;

-- Verify seed
DO $$
BEGIN
  RAISE NOTICE 'Seed complete. Available slots: %',
    CASE WHEN EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'slots')
         THEN (SELECT COUNT(*)::text FROM slots WHERE status = 'AVAILABLE')
         ELSE 'table not yet created' END;
END;
$$;
