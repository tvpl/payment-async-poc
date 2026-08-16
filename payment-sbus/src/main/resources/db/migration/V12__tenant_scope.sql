-- TEN-06: idempotency uniqueness is scoped by tenant, never global. Existing rows predate
-- tenant identity and are attributed to a synthetic 'legacy' tenant so the composite unique
-- constraint below has no ambiguity to resolve for them (the old key was globally unique, so
-- 'legacy' + idempotency_key stays unique too).
ALTER TABLE payment_sbus_message ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'legacy';
ALTER TABLE idempotency_record ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'legacy';

ALTER TABLE idempotency_record DROP CONSTRAINT uq_idempotency_record_key;
ALTER TABLE idempotency_record ADD CONSTRAINT uq_idem_tenant_key UNIQUE (tenant_id, idempotency_key);
