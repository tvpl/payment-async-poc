package com.example.platform.asyncredis.config;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}
