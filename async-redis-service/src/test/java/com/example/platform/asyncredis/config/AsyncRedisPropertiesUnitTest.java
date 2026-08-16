package com.example.platform.asyncredis.config;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-02: the wait pool's bounds are an invariant, not a suggestion. A pool with no capacity, or one
 * whose acquisition timeout is not a positive finite duration, cannot apply backpressure at all — so
 * it is refused at startup rather than discovered under load.
 */
class AsyncRedisPropertiesUnitTest {

    private static AsyncRedisProperties valid() {
        AsyncRedisProperties props = new AsyncRedisProperties();
        props.setPoolMaxTotal(8);
        props.setPoolMaxWait(Duration.ofMillis(500));
        return props;
    }

    @Test
    void aPoolWithNoCapacityIsRefusedAtStartup() {
        AsyncRedisProperties props = valid();
        props.setPoolMaxTotal(0);

        ConfigurationException thrown =
                assertThrows(ConfigurationException.class, props::validateRetention);
        assertTrue(thrown.getMessage().contains("async.redis.pool-max-total"),
                "the failure must name the offending property; was: " + thrown.getMessage());
    }

    @Test
    void aNegativePoolCapacityIsRefusedAtStartup() {
        AsyncRedisProperties props = valid();
        props.setPoolMaxTotal(-1);

        ConfigurationException thrown =
                assertThrows(ConfigurationException.class, props::validateRetention);
        assertTrue(thrown.getMessage().contains("async.redis.pool-max-total"),
                "the failure must name the offending property; was: " + thrown.getMessage());
    }

    @Test
    void aZeroAcquisitionTimeoutIsRefusedAtStartup() {
        AsyncRedisProperties props = valid();
        props.setPoolMaxWait(Duration.ZERO);

        ConfigurationException thrown =
                assertThrows(ConfigurationException.class, props::validateRetention);
        assertTrue(thrown.getMessage().contains("async.redis.pool-max-wait"),
                "the failure must name the offending property; was: " + thrown.getMessage());
    }

    @Test
    void aNegativeAcquisitionTimeoutIsRefusedAtStartup() {
        AsyncRedisProperties props = valid();
        props.setPoolMaxWait(Duration.ofMillis(-1));

        ConfigurationException thrown =
                assertThrows(ConfigurationException.class, props::validateRetention);
        assertTrue(thrown.getMessage().contains("async.redis.pool-max-wait"),
                "the failure must name the offending property; was: " + thrown.getMessage());
    }

    /**
     * AUD-20: a status shorter-lived than the idempotency reservation it backs leaves a replay
     * window where the reservation still resolves a jobId, but that job's status key has already
     * expired — a later replay of the same Idempotency-Key would then find nothing to answer with.
     */
    @Test
    void aStatusTtlShorterThanTheIdempotencyTtlIsRefusedAtStartup() {
        AsyncRedisProperties props = valid();
        props.setStatusTtl(Duration.ofHours(1));
        props.setIdempotencyTtl(Duration.ofHours(2));

        ConfigurationException thrown =
                assertThrows(ConfigurationException.class, props::validateRetention);
        assertTrue(thrown.getMessage().contains("async.redis.status-ttl"),
                "the failure must name the offending property; was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("idempotency-ttl"),
                "the failure must name the property it was compared against; was: "
                        + thrown.getMessage());
    }

    @Test
    void aStatusTtlEqualToTheIdempotencyTtlIsAccepted() {
        AsyncRedisProperties props = valid();
        props.setStatusTtl(Duration.ofHours(2));
        props.setIdempotencyTtl(Duration.ofHours(2));
        props.setResultTtl(Duration.ofMinutes(1));

        assertDoesNotThrow(props::validateRetention,
                "status-ttl equal to idempotency-ttl satisfies >= and must boot");
    }
}
