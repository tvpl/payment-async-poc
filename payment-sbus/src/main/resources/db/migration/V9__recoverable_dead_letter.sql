ALTER TABLE outbox_event
    ADD COLUMN claim_token UUID,
    ADD COLUMN dlq_started_at TIMESTAMPTZ;

CREATE INDEX idx_outbox_event_dlq_pending
    ON outbox_event (next_attempt_at, created_at)
    WHERE status = 'DLQ_PENDING';
