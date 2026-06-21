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
     * lets multiple SBUS instances poll the outbox concurrently without stepping on
     * each other — each instance grabs a disjoint set of rows. Must run in a tx.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(Instant now, int limit);

    long countByStatus(OutboxStatus status);
}
