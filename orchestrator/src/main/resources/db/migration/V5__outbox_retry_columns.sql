-- Adds retry tracking and dead-letter support to outbox_events.
--
-- Without these columns, a permanently undeliverable event (e.g., a malformed
-- payload that the Kafka broker always rejects) will be polled forever at 1-second
-- intervals, saturating logs and degrading relay throughput indefinitely.
--
-- Retry strategy (implemented in OutboxRelayService):
--   attempt 1 → next_retry_at + 2s
--   attempt 2 → next_retry_at + 4s
--   attempt 3 → next_retry_at + 8s
--   attempt 4 → next_retry_at + 16s
--   attempt 5 → marked as dead letter (dead_letter_at set, excluded from polling)
--
-- Dead-letter events require manual inspection or a separate DLQ consumer.

ALTER TABLE outbox_events
    ADD COLUMN attempt_count  INT          NOT NULL DEFAULT 0,
    ADD COLUMN last_error     TEXT,
    ADD COLUMN next_retry_at  TIMESTAMPTZ,
    ADD COLUMN dead_letter_at TIMESTAMPTZ;

-- Replace the existing partial index: now also filters out dead-letter events
-- and respects the next_retry_at backoff window.
DROP INDEX IF EXISTS idx_outbox_unpublished;

CREATE INDEX idx_outbox_pending
    ON outbox_events (next_retry_at NULLS FIRST, created_at)
    WHERE published_at IS NULL
      AND dead_letter_at IS NULL;

COMMENT ON COLUMN outbox_events.attempt_count  IS 'Number of failed publish attempts. Drives exponential backoff.';
COMMENT ON COLUMN outbox_events.last_error     IS 'Error message from the last failed publish attempt.';
COMMENT ON COLUMN outbox_events.next_retry_at  IS 'Earliest time to retry. NULL = retry immediately. Set by relay on failure.';
COMMENT ON COLUMN outbox_events.dead_letter_at IS 'Non-null = max attempts reached; excluded from relay polling. Requires manual intervention.';
