package com.example.payments.sbus.repository;

import com.example.payments.sbus.domain.PaymentSbusMessage;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface PaymentSbusMessageRepository extends CrudRepository<PaymentSbusMessage, Long> {

    Optional<PaymentSbusMessage> findByRequestId(String requestId);

    Optional<PaymentSbusMessage> findBySimulationId(String simulationId);

    /** Retention: purge old terminal simulations (the durable fallback only needs recent ones). */
    @Query(value = """
            DELETE FROM payment_sbus_message
            WHERE id IN (
                SELECT id FROM payment_sbus_message
                WHERE status IN ('COMPLETED','FAILED') AND updated_at < :threshold
                LIMIT :limit)
            """, nativeQuery = true)
    int deleteTerminalUpdatedBefore(Instant threshold, int limit);
}
