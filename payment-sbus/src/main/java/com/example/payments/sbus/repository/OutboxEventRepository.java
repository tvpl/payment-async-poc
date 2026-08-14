package com.example.payments.sbus.repository;

import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
            WHERE status IN ('PENDING', 'DLQ_PENDING') AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(Instant now, int limit);

    /**
     * Renews the lease of a row still owned by the current claim (AUD-07): called for every
     * remaining claimed row after each row's own publish turn completes, so a slow batch never
     * outlives its own lease mid-way through. Fenced the same way as every other claim-owner
     * mutation here — a stale token (something else already reclaimed the row) affects zero rows.
     */
    @Query(value = """
            UPDATE outbox_event
            SET claimed_at = :now
            WHERE id = :id AND claim_token = :claimToken AND status = 'IN_PROGRESS'
            """, nativeQuery = true)
    int renewClaim(long id, UUID claimToken, Instant now);

    /** Fenced completion: only the current lease owner may publish the row. */
    @Query(value = """
            UPDATE outbox_event
            SET status = CASE WHEN topic = 'payment.simulation.dlq'
                              THEN 'DLQ_PUBLISHED' ELSE 'PUBLISHED' END,
                published_at = :now, claimed_at = NULL, claim_token = NULL, last_error = NULL
            WHERE id = :id AND status = 'IN_PROGRESS' AND claim_token = :claimToken
            """, nativeQuery = true)
    int markPublished(long id, UUID claimToken, Instant now);

    /** Fenced failure: a stale publisher cannot revert a newer owner or terminal state. */
    @Query(value = """
            UPDATE outbox_event
            SET status = :status, topic = :topic, headers = CAST(:headers AS jsonb),
                attempts = :attempts, next_attempt_at = :nextAttemptAt,
                dlq_started_at = CASE WHEN :topic = 'payment.simulation.dlq'
                                      THEN COALESCE(dlq_started_at, CURRENT_TIMESTAMP)
                                      ELSE dlq_started_at END,
                last_error = :lastError, claimed_at = NULL, claim_token = NULL
            WHERE id = :id AND status = 'IN_PROGRESS' AND claim_token = :claimToken
            """, nativeQuery = true)
    int markFailedAttempt(long id, UUID claimToken, String status, String topic,
                          String headers, int attempts, Instant nextAttemptAt,
                          String lastError);

    /** Fenced throttle release for the current claim owner. */
    @Query(value = """
            UPDATE outbox_event
            SET status = :status, next_attempt_at = :nextAttemptAt,
                claimed_at = NULL, claim_token = NULL
            WHERE id = :id AND status = 'IN_PROGRESS' AND claim_token = :claimToken
            """, nativeQuery = true)
    int releaseClaim(long id, UUID claimToken, String status, Instant nextAttemptAt);

    /**
     * Locks a bounded batch of rows stuck IN_PROGRESS past their claim lease, for
     * {@code OutboxReaper} to reclaim one by one — each reclaim goes through
     * {@code OutboxClaimService#markFailure} exactly like a real publish failure would (AUD-08:
     * {@code attempts} incremented, backoff applied, exhaustion routes to the DLQ), instead of a
     * blind reset that let a permanently-stuck row loop hot forever. {@code FOR UPDATE SKIP
     * LOCKED} lets multiple SBUS instances reclaim concurrently without stepping on each other,
     * same discipline as {@link #lockPendingBatch}.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE status = 'IN_PROGRESS' AND claimed_at < :threshold
            ORDER BY claimed_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findStuckBatch(Instant threshold, int limit);

    /** Housekeeping: purge successfully published rows older than the retention window. */
    @Query(value = """
            DELETE FROM outbox_event
            WHERE status IN ('PUBLISHED', 'DLQ_PUBLISHED') AND published_at < :threshold
            """, nativeQuery = true)
    int deletePublishedBefore(Instant threshold);

    long countByStatus(OutboxStatus status);

    @Query(value = """
            SELECT count(*) FROM outbox_event
            WHERE topic = 'payment.simulation.dlq'
              AND status IN ('DLQ_PENDING', 'IN_PROGRESS')
            """, nativeQuery = true)
    long countUnconfirmedDeadLetters();

    @Query(value = """
            SELECT COALESCE(EXTRACT(EPOCH FROM
                (CURRENT_TIMESTAMP - MIN(COALESCE(dlq_started_at, created_at)))), 0)::bigint
            FROM outbox_event
            WHERE topic = 'payment.simulation.dlq'
              AND status IN ('DLQ_PENDING', 'IN_PROGRESS')
            """, nativeQuery = true)
    long oldestUnconfirmedDeadLetterAgeSeconds();

    @Query(value = """
            INSERT INTO outbox_event (
                aggregate_type, aggregate_id, event_type, topic, message_key,
                payload, headers, status, attempts, next_attempt_at, deduplication_key)
            VALUES (
                'KafkaRetry', :aggregateId, 'KafkaRetryScheduled', :topic, :messageKey,
                :payload, CAST(:headers AS jsonb), 'PENDING', 0, :nextAttemptAt, :deduplicationKey)
            ON CONFLICT (deduplication_key) WHERE deduplication_key IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insertDurableRetry(String aggregateId, String topic, String messageKey,
                           byte[] payload, String headers, Instant nextAttemptAt,
                           String deduplicationKey);

    @Query(value = """
            INSERT INTO outbox_event (
                aggregate_type, aggregate_id, event_type, topic, message_key,
                payload, headers, status, attempts, next_attempt_at, deduplication_key,
                dlq_started_at)
            VALUES (
                'KafkaDeadLetter', :aggregateId, 'KafkaDeadLetterScheduled',
                'payment.simulation.dlq', :messageKey, :payload, CAST(:headers AS jsonb),
                'DLQ_PENDING', 0, :nextAttemptAt, :deduplicationKey, :nextAttemptAt)
            ON CONFLICT (deduplication_key) WHERE deduplication_key IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insertDurableDeadLetter(String aggregateId, String messageKey, byte[] payload,
                                String headers, Instant nextAttemptAt,
                                String deduplicationKey);
}
