package com.example.payments.api.config;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiPropertiesUnitTest {

    /** IDEM-03: the idempotency window published in the contract defaults to 24h at the Edge. */
    @Test
    void defaultIdempotencyTtlIsTheTwentyFourHourContractWindow() {
        assertEquals(Duration.ofHours(24), new ApiProperties().getIdempotencyTtl());
    }

    private static ApiProperties properties(Duration waitTimeout, Duration statusTtl, Duration idempotencyTtl) {
        ApiProperties properties = new ApiProperties();
        properties.setWaitTimeout(waitTimeout);
        properties.setStatusTtl(statusTtl);
        properties.setIdempotencyTtl(idempotencyTtl);
        return properties;
    }

    @Test
    void acceptsEqualIdempotencyAndStatusTtl() {
        assertDoesNotThrow(() -> properties(
                Duration.ofSeconds(3), Duration.ofMinutes(15), Duration.ofMinutes(15)).validate());
    }

    @Test
    void acceptsIdempotencyTtlLongerThanStatusTtl() {
        assertDoesNotThrow(() -> properties(
                Duration.ofSeconds(3), Duration.ofMinutes(15), Duration.ofMinutes(30)).validate());
    }

    @Test
    void rejectsIdempotencyTtlShorterThanStatusTtl() {
        assertThrows(ConfigurationException.class, () -> properties(
                Duration.ofSeconds(3), Duration.ofMinutes(15), Duration.ofMinutes(5)).validate());
    }

    @Test
    void rejectsNonPositiveIdempotencyTtl() {
        assertThrows(ConfigurationException.class, () -> properties(
                Duration.ofSeconds(3), Duration.ofMinutes(15), Duration.ZERO).validate());
    }

    @Test
    void rejectsNonPositiveStatusTtl() {
        assertThrows(ConfigurationException.class, () -> properties(
                Duration.ofSeconds(3), Duration.ZERO, Duration.ofMinutes(15)).validate());
    }

    @Test
    void rejectsNonPositiveWaitTimeout() {
        assertThrows(ConfigurationException.class, () -> properties(
                Duration.ZERO, Duration.ofMinutes(15), Duration.ofMinutes(15)).validate());
    }

    @Test
    void rejectsNonPositivePublishLease() {
        ApiProperties properties = properties(
                Duration.ofSeconds(3), Duration.ofMinutes(15), Duration.ofMinutes(15));
        properties.setPublishLease(Duration.ZERO);

        assertThrows(ConfigurationException.class, properties::validate);
    }
}
