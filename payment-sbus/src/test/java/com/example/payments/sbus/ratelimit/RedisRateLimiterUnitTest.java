package com.example.payments.sbus.ratelimit;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisRateLimiterUnitTest {

    @Test
    void redisOutageFailsClosedInsteadOfMultiplyingCapacityPerInstance() {
        Supplier<RedisCommands<String, String>> unavailable = () -> {
            throw new IllegalStateException("redis unavailable");
        };
        RedisRateLimiter limiter = new RedisRateLimiter(unavailable, "core-command", 50, 1_000L);

        assertThrows(IllegalStateException.class, limiter::tryAcquire);
    }
}
