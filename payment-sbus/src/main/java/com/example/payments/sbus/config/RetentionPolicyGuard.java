package com.example.payments.sbus.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.exceptions.ConfigurationException;

import java.time.Duration;

/** Refuses cleanup windows that can erase deduplication or status before redelivery ends. */
@Context
public final class RetentionPolicyGuard {

    public RetentionPolicyGuard(
            HousekeepingProperties housekeeping,
            OutboxProperties outbox,
            @Value("${sbus.retention.kafka-topic:7d}") Duration kafkaTopicRetention,
            @Value("${sbus.retention.max-redelivery-window:1d}") Duration maxRedeliveryWindow) {
        validate(housekeeping.getIdempotencyRetention(), housekeeping.getMessageRetention(),
                outbox.getRetention(), kafkaTopicRetention, maxRedeliveryWindow);
    }

    static void validate(Duration idempotency, Duration state, Duration outbox,
                         Duration topic, Duration redelivery) {
        requirePositive("idempotency retention", idempotency);
        requirePositive("state retention", state);
        requirePositive("outbox retention", outbox);
        requirePositive("Kafka topic retention", topic);
        requirePositive("maximum redelivery window", redelivery);
        Duration replayWindow = topic.compareTo(redelivery) >= 0 ? topic : redelivery;
        if (idempotency.compareTo(replayWindow) < 0) {
            throw new ConfigurationException(
                    "Idempotency retention must cover Kafka topic retention and maximum redelivery window");
        }
        if (state.compareTo(idempotency) < 0) {
            throw new ConfigurationException("State retention must be at least idempotency retention");
        }
        if (outbox.compareTo(redelivery) < 0) {
            throw new ConfigurationException("Published outbox retention must cover maximum redelivery window");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new ConfigurationException(name + " must be positive");
        }
    }
}
