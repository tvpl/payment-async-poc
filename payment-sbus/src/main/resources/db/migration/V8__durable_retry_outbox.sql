ALTER TABLE outbox_event
    ADD COLUMN deduplication_key VARCHAR(255);

CREATE UNIQUE INDEX uq_outbox_event_deduplication_key
    ON outbox_event (deduplication_key)
    WHERE deduplication_key IS NOT NULL;
