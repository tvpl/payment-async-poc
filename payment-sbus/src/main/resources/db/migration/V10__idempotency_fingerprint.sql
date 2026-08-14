-- Nullable: existing rows predate fingerprinting and are treated as non-replay targets
-- (PaymentPersistenceService#findReplayTarget compares fingerprints and rejects a null match).
ALTER TABLE idempotency_record
    ADD COLUMN fingerprint VARCHAR(64);
