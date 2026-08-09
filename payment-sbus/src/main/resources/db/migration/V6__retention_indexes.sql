-- Support efficient retention purges.
CREATE INDEX IF NOT EXISTS idx_idempotency_record_created_at ON idempotency_record (created_at);
CREATE INDEX IF NOT EXISTS idx_payment_sbus_message_status_updated_at
    ON payment_sbus_message (status, updated_at);
