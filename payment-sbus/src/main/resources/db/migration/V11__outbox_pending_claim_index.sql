-- Speeds the PENDING-status claim query (WHERE status IN ('PENDING', 'DLQ_PENDING') AND
-- next_attempt_at <= :now ORDER BY created_at) for the far more common PENDING branch —
-- V9 already added the equivalent partial index for DLQ_PENDING.
CREATE INDEX idx_outbox_event_pending
    ON outbox_event (next_attempt_at, created_at)
    WHERE status = 'PENDING';
