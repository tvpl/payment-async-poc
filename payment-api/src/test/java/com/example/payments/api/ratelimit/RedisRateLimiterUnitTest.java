package com.example.payments.api.ratelimit;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * With Redis gone the limiter must fail closed onto a share of the budget, not hand the whole
 * budget to every instance (CAP-03).
 */
class RedisRateLimiterUnitTest {

    private static RedisRateLimiter withoutRedis(int limitForPeriod, int instances) {
        return new RedisRateLimiter(
                () -> {
                    throw new IllegalStateException("Redis unavailable");
                },
                "test", limitForPeriod, instances, 60_000L);
    }

    @Test
    void aRedisOutageLeavesEachInstanceOnlyItsShareOfTheBudget() {
        RedisRateLimiter limiter = withoutRedis(8, 4);

        assertEquals(2, limiter.degradedLimitForPeriod());
        assertTrue(limiter.tryAcquire("scope"));
        assertTrue(limiter.tryAcquire("scope"));
        assertFalse(limiter.tryAcquire("scope"),
                "a single instance must not admit the whole fleet budget while Redis is down");
    }

    @Test
    void aRedisOutageNeverLeavesAnInstanceWithZeroBudget() {
        RedisRateLimiter limiter = withoutRedis(2, 8);

        assertEquals(1, limiter.degradedLimitForPeriod());
        assertTrue(limiter.tryAcquire("scope"));
        assertFalse(limiter.tryAcquire("scope"));
    }

    @Test
    void aSingleInstanceFleetKeepsItsFullBudgetWhenRedisIsDown() {
        RedisRateLimiter limiter = withoutRedis(3, 1);

        assertEquals(3, limiter.degradedLimitForPeriod());
        assertTrue(limiter.tryAcquire("scope"));
        assertTrue(limiter.tryAcquire("scope"));
        assertTrue(limiter.tryAcquire("scope"));
        assertFalse(limiter.tryAcquire("scope"));
    }

    @Test
    void scopesAreCountedIndependentlyWhileRedisIsDown() {
        RedisRateLimiter limiter = withoutRedis(4, 4);

        assertTrue(limiter.tryAcquire("tenant-a"));
        assertFalse(limiter.tryAcquire("tenant-a"));
        assertTrue(limiter.tryAcquire("tenant-b"),
                "one tenant's exhausted window must not reject another tenant");
    }

    @Test
    void theScopeIsPartOfTheDistributedKeySoBudgetsDoNotCollide() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.<Long>eval(anyString(), any(), any(String[].class), any(String[].class)))
                .thenReturn(1L);
        RedisRateLimiter limiter = new RedisRateLimiter(() -> commands, "api-admission", 10, 1, 1_000L);

        limiter.tryAcquire("tenant-a");
        limiter.tryAcquire("tenant-b");

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(commands, times(2))
                .eval(anyString(), any(), keys.capture(), any(String[].class));
        assertTrue(keys.getAllValues().get(0)[0].startsWith("rl:api-admission:tenant-a:"));
        assertTrue(keys.getAllValues().get(1)[0].startsWith("rl:api-admission:tenant-b:"));
    }
}
