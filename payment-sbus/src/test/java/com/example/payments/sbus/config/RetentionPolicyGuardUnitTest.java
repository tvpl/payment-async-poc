package com.example.payments.sbus.config;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetentionPolicyGuardUnitTest {

    @Test
    void acceptsRetentionThatCoversReplayAndPublicationWindows() {
        assertDoesNotThrow(() -> validate(Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofDays(3), Duration.ofDays(7), Duration.ofDays(1)));
    }

    @Test
    void rejectsIdempotencyExpiringBeforeKafkaReplayEnds() {
        assertThrows(ConfigurationException.class, () -> validate(Duration.ofDays(6), Duration.ofDays(30),
                Duration.ofDays(3), Duration.ofDays(7), Duration.ofDays(1)));
    }

    @Test
    void rejectsStatusExpiringBeforeDeduplication() {
        assertThrows(ConfigurationException.class, () -> validate(Duration.ofDays(7), Duration.ofDays(6),
                Duration.ofDays(3), Duration.ofDays(7), Duration.ofDays(1)));
    }

    @Test
    void rejectsPublishedOutboxExpiringInsideRedeliveryWindow() {
        assertThrows(ConfigurationException.class, () -> validate(Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofHours(23), Duration.ofDays(7), Duration.ofDays(1)));
    }

    private static void validate(Duration idempotency, Duration state, Duration outbox,
                                 Duration topic, Duration redelivery) {
        RetentionPolicyGuard.validate(idempotency, state, outbox, topic, redelivery);
    }
}
