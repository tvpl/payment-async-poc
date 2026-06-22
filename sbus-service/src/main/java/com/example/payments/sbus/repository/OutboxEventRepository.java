package com.example.payments.sbus.repository;

import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface OutboxEventRepository extends CrudRepository<OutboxEvent, Long> {

    /**
     * Atomically claims a batch of due, pending events. {@code FOR UPDATE SKIP LOCKED}
     * lets multiple SBUS instances poll concurrently without stepping on each other.
     * Must run in a (short) tx; the caller flips them to IN_PROGRESS and commits
     * before doing the slow Kafka publish outside the transaction.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(Instant now, int limit);

    /** Marks a whole batch as PUBLISHED in one statement (happy path of the dispatcher). */
    @Query(value = """
            UPDATE outbox_event
            SET status = 'PUBLISHED', published_at = :now, claimed_at = NULL, last_error = NULL
            WHERE id IN (:ids)
            """, nativeQuery = true)
    int markPublished(java.util.Collection<Long> ids, Instant now);

    /** Reclaims rows stuck IN_PROGRESS (publisher crashed mid-flight) back to PENDING. */
    @Query(value = """
            UPDATE outbox_event SET status = 'PENDING', claimed_at = NULL
            WHERE status = 'IN_PROGRESS' AND claimed_at < :threshold
            """, nativeQuery = true)
    int reclaimStuck(Instant threshold);

    /** Housekeeping: purge successfully published rows older than the retention window. */
    @Query(value = """
            DELETE FROM outbox_event
            WHERE status = 'PUBLISHED' AND published_at < :threshold
            """, nativeQuery = true)
    int deletePublishedBefore(Instant threshold);

    long countByStatus(OutboxStatus status);
}
