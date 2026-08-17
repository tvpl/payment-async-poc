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
        when(commands.<Long>evalsha(anyString(), any(), any(String[].class), any(String[].class)))
                .thenReturn(1L);
        RedisRateLimiter limiter = new RedisRateLimiter(() -> commands, "api-admission", 10, 1, 1_000L);

        limiter.tryAcquire("tenant-a");
        limiter.tryAcquire("tenant-b");

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(commands, times(2))
                .evalsha(anyString(), any(), keys.capture(), any(String[].class));
        assertTrue(keys.getAllValues().get(0)[0].startsWith("rl:api-admission:tenant-a:"));
        assertTrue(keys.getAllValues().get(1)[0].startsWith("rl:api-admission:tenant-b:"));
    }

    /**
     * SCAL-06: EVALSHA is the primary path (skips resending the script body); a NOSCRIPT reply
     * falls back to a plain EVAL for that same call instead of denying the request that
     * triggered the reload.
     */
    @Test
    void aNoScriptReplyFallsBackToEvalInsteadOfDenyingTheCurrentRequest() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.<Long>evalsha(anyString(), any(), any(String[].class), any(String[].class)))
                .thenThrow(new io.lettuce.core.RedisNoScriptException("NOSCRIPT"));
        when(commands.<Long>eval(anyString(), any(), any(String[].class), any(String[].class)))
                .thenReturn(1L);
        RedisRateLimiter limiter = new RedisRateLimiter(() -> commands, "api-admission", 10, 1, 1_000L);

        boolean admitted = limiter.tryAcquire("tenant-a");

        assertTrue(admitted, "a NOSCRIPT reply must not deny the current request");
        verify(commands).eval(anyString(), any(), any(String[].class), any(String[].class));
    }

    /**
     * SCAL-06: the sliding-window estimate itself is evaluated inside the Lua script (only
     * provable end-to-end against real Redis - see the IT), but the Java side must feed the
     * script the previous window's key (one window index behind the current one) and a
     * previous-window weight in [0, 1] for the script to be able to compute it at all.
     */
    @Test
    void computesAndPassesThePreviousWindowKeyAndAWeightBetweenZeroAndOneToTheScript() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.<Long>evalsha(anyString(), any(), any(String[].class), any(String[].class)))
                .thenReturn(1L);
        RedisRateLimiter limiter = new RedisRateLimiter(() -> commands, "api-admission", 10, 1, 1_000L);

        limiter.tryAcquire("tenant-a");

        ArgumentCaptor<String[]> keysCaptor = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String[]> argsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(commands).evalsha(anyString(), any(), keysCaptor.capture(), argsCaptor.capture());
        String[] keys = keysCaptor.getValue();
        String[] args = argsCaptor.getValue();
        assertEquals(2, keys.length, "current-window key and previous-window key");
        long currentWindow = Long.parseLong(keys[0].substring(keys[0].lastIndexOf(':') + 1));
        long previousWindow = Long.parseLong(keys[1].substring(keys[1].lastIndexOf(':') + 1));
        assertEquals(currentWindow - 1, previousWindow);
        double weightPrevious = Double.parseDouble(args[2]);
        assertTrue(weightPrevious >= 0.0 && weightPrevious <= 1.0,
                "weightPrevious out of [0,1]: " + weightPrevious);
    }
}
