package com.example.payments.sbus.repository;

import com.example.payments.sbus.domain.PaymentSbusMessage;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface PaymentSbusMessageRepository extends CrudRepository<PaymentSbusMessage, Long> {

    Optional<PaymentSbusMessage> findByRequestId(String requestId);

    Optional<PaymentSbusMessage> findBySimulationId(String simulationId);

    /**
     * A simulationId can be shared by more than one row: when an idempotency-key replay arrives
     * with a fresh requestId (its original mapping expired client-side, e.g. payment-api's
     * Redis idempotency-ttl outliving payment_sbus_message's own idempotency window), the new
     * requestId is recorded against the SAME simulationId rather than starting a disconnected
     * simulation. {@code simulation_id} has no unique constraint for exactly this reason —
     * {@link #findBySimulationId} stays for the single-row call sites that predate replay
     * support; finalization must use this one so every requestId sharing a simulation gets its
     * own terminal event, not just whichever row a single-result query happened to pick.
     */
    List<PaymentSbusMessage> findAllBySimulationId(String simulationId);

    /**
     * Targets one row by primary key, not by {@code simulation_id} alone: a simulationId can now
     * be shared by more than one row (an idempotency-key replay records its fresh requestId
     * against the same simulation — see {@code PaymentPersistenceService}), and each row carries
     * its own independent {@code version}. Filtering on {@code id} makes "finalize this specific
     * row" explicit instead of relying on version uniqueness alone to disambiguate.
     */
    @Query(value = """
            UPDATE payment_sbus_message
            SET status = :status,
                error_code = :errorCode,
                error_message = :errorMessage,
                result = CAST(:resultJson AS jsonb),
                version = version + 1,
                updated_at = :updatedAt
            WHERE id = :id
              AND status = 'PROCESSING'
              AND version = :expectedVersion
            """, nativeQuery = true)
    int finalizeIfProcessing(long id, long expectedVersion, String status,
                             @Nullable String errorCode, @Nullable String errorMessage, String resultJson,
                             Instant updatedAt);

    /** Retention: purge old terminal simulations (the durable fallback only needs recent ones). */
    @Query(value = """
            DELETE FROM payment_sbus_message
            WHERE id IN (
                SELECT id FROM payment_sbus_message
                WHERE status IN ('COMPLETED','FAILED') AND updated_at < :threshold
                LIMIT :limit)
            """, nativeQuery = true)
    int deleteTerminalUpdatedBefore(Instant threshold, int limit);

    /** RES-02: how many rows are still eligible after a housekeeping run stopped (drained or capped). */
    @Query(value = """
            SELECT count(*) FROM payment_sbus_message
            WHERE status IN ('COMPLETED','FAILED') AND updated_at < :threshold
            """, nativeQuery = true)
    long countTerminalUpdatedBefore(Instant threshold);
}
