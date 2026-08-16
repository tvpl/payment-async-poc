package com.example.platform.asyncredis.ratelimit;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.redis.RedisConnections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncRateLimiterUnitTest {

    /** A RedisConnections with no client — shared() throws, forcing the local-fallback path. */
    private RedisConnections failingRedis(AsyncRedisProperties props) {
        return new RedisConnections(null, props);
    }

    @Test
    void disabledLimiterAlwaysAllows() {
        AsyncRedisProperties props = new AsyncRedisProperties();
        props.setAdmissionLimitPerSec(0);
        AsyncRateLimiter limiter = new AsyncRateLimiter(failingRedis(props), props);
        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.tryAcquire());
        }
    }

    @Test
    void localFallbackEnforcesLimitWithinWindow() {
        AsyncRedisProperties props = new AsyncRedisProperties();
        props.setAdmissionLimitPerSec(3);
        AsyncRateLimiter limiter = new AsyncRateLimiter(failingRedis(props), props);
        // Redis is unavailable -> degrades to a local fixed-window counter (fail-degraded).
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "4th request in the same window must be rejected");
    }

    @Test
    void localFallbackDividesTheBudgetSoAFleetDoesNotMultiplyTheApprovedBurst() {
        AsyncRedisProperties props = new AsyncRedisProperties();
        props.setAdmissionLimitPerSec(9);
        props.setInstances(3);
        AsyncRateLimiter limiter = new AsyncRateLimiter(failingRedis(props), props);

        // Each of the 3 instances may admit 9/3 = 3, so the fleet still admits 9 — not 27.
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(),
                "a single instance must not admit the whole fleet budget while Redis is down");
    }

    @Test
    void degradedBudgetNeverDropsBelowOne() {
        AsyncRedisProperties props = new AsyncRedisProperties();
        props.setAdmissionLimitPerSec(2);
        props.setInstances(8);
        AsyncRateLimiter limiter = new AsyncRateLimiter(failingRedis(props), props);

        // 2/8 truncates to 0; the floor keeps the service admitting at least one request rather
        // than turning a Redis outage into a total outage.
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }
}
