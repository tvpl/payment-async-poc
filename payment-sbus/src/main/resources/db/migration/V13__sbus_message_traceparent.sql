-- OBS-02: persists the W3C traceparent captured at this row's own ingestion so the final event
-- published for it later (once the Core responds, possibly much after ingestion) can still carry
-- a valid trace context instead of going out with none. Nullable: a record ingested with no
-- traceparent header stays untraced, exactly like today.
ALTER TABLE payment_sbus_message ADD COLUMN traceparent VARCHAR(64);
