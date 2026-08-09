-- Outbox now stores the self-describing Avro bytes (schema id embedded), so the
-- publisher can send them to Kafka untouched. Payload changes jsonb -> bytea.
ALTER TABLE outbox_event DROP COLUMN payload;
ALTER TABLE outbox_event ADD COLUMN payload BYTEA NOT NULL;

-- Lease timestamp for the claim-then-publish pattern (publish happens OUTSIDE the
-- DB transaction; IN_PROGRESS rows older than the lease are reclaimed).
ALTER TABLE outbox_event ADD COLUMN claimed_at TIMESTAMPTZ;
