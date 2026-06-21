package com.example.payments.sbus.repository;

import com.example.payments.sbus.domain.PaymentSbusMessage;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface PaymentSbusMessageRepository extends CrudRepository<PaymentSbusMessage, Long> {

    Optional<PaymentSbusMessage> findByRequestId(String requestId);

    Optional<PaymentSbusMessage> findBySimulationId(String simulationId);
}
