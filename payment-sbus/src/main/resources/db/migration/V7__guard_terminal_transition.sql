ALTER TABLE payment_sbus_message
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payment_sbus_message
    ADD CONSTRAINT ck_payment_sbus_message_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'));
