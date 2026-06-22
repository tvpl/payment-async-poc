package com.example.payments.common.ratelimit;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRateLimiterUnitTest {

    /** When Redis is unavailable, the limiter must still bound the rate locally (fail-degraded). */
    @Test
    void fallsBackToLocalWindowWhenRedisDown() {
        Supplier<RedisCommands<String, String>> down = () -> {
            throw new RuntimeException("redis down");
        };
        // limit 2 per very large window so the window doesn't roll during the test
        RedisRateLimiter limiter = new RedisRateLimiter(down, "test", 2, 3_600_000L);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "third call in the window must be denied by local fallback");
    }
}
