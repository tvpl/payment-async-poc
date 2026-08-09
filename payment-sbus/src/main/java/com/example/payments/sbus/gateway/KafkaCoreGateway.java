package com.example.payments.sbus.gateway;

import jakarta.inject.Singleton;

/**
 * Default {@link CoreGateway}: the Core is decoupled behind the outbox + Kafka
 * command/response topics. No direct call is made from request handling — the
 * outbox owns reliable delivery.
 */
@Singleton
public class KafkaCoreGateway implements CoreGateway {

    @Override
    public String description() {
        return "kafka-outbox: payment.simulation.core.command -> core -> payment.simulation.core.response";
    }
}
