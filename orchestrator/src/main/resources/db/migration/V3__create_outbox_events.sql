CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ
);

-- Partial index: covers only unpublished events — keeps relay query O(1) regardless of table size
CREATE INDEX idx_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;

COMMENT ON TABLE outbox_events IS 'Transactional Outbox — events written in same tx as business entity, published to Kafka by relay.';
COMMENT ON COLUMN outbox_events.payload IS 'JSON event payload. No PHI — only IDs, status, timestamps (data minimisation).';
COMMENT ON COLUMN outbox_events.published_at IS 'NULL = pending. Set by OutboxRelayService on successful Kafka publish.';
