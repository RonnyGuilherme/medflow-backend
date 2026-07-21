-- Automatic updated_at maintenance via PostgreSQL triggers.
--
-- Without triggers, updated_at relies entirely on the application correctly
-- calling setUpdatedAt() before every save. A missed call silently leaves the
-- field at its initial value, breaking audit trails and cache invalidation.
-- The trigger is the authoritative enforcement layer — application calls are
-- now redundant (belt-and-suspenders) rather than the sole protection.

-- Shared trigger function reused by multiple tables
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger on appointments
CREATE TRIGGER trg_appointments_updated_at
    BEFORE UPDATE ON appointments
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- Trigger on slots
CREATE TRIGGER trg_slots_updated_at
    BEFORE UPDATE ON slots
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON FUNCTION set_updated_at() IS
    'Trigger function: automatically sets updated_at = NOW() on any UPDATE. '
    'Shared across appointments and slots tables.';
