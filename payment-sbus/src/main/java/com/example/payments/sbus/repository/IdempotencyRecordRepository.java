package com.example.payments.sbus.repository;

import com.example.payments.sbus.domain.IdempotencyRecord;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface IdempotencyRecordRepository extends CrudRepository<IdempotencyRecord, Long> {

    /**
     * TEN-06: post-migration, {@code idempotency_key} is unique only within a tenant — the same
     * key legitimately exists for more than one tenant. Callers resolving replay/dedup MUST scope
     * by {@code (tenantId, idempotencyKey)}, never by key alone.
     */
    Optional<IdempotencyRecord> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    /** Retention: purge old idempotency records (bounded batch keeps locks short). */
    @Query(value = """
            DELETE FROM idempotency_record
            WHERE id IN (SELECT id FROM idempotency_record WHERE created_at < :threshold LIMIT :limit)
            """, nativeQuery = true)
    int deleteCreatedBefore(Instant threshold, int limit);

    /** RES-02: how many rows are still eligible after a housekeeping run stopped (drained or capped). */
    @Query(value = "SELECT count(*) FROM idempotency_record WHERE created_at < :threshold", nativeQuery = true)
    long countCreatedBefore(Instant threshold);
}
