-- Durable copy of the final result so the API can fall back to the SBUS when the
-- Redis entry is gone (TTL expired) or was never written (missed final event).
ALTER TABLE payment_sbus_message ADD COLUMN result JSONB;
