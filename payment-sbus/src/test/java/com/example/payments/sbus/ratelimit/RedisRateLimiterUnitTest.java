package com.example.payments.sbus.ratelimit;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimiterUnitTest {

    @Test
    void redisOutageFailsClosedInsteadOfMultiplyingCapacityPerInstance() {
        Supplier<RedisCommands<String, String>> unavailable = () -> {
            throw new IllegalStateException("redis unavailable");
        };
        RedisRateLimiter limiter = new RedisRateLimiter(unavailable, "core-command", 50, 1_000L);

        assertThrows(IllegalStateException.class, limiter::tryAcquire);
    }

    /**
     * T33/SCAL-06 (mirrors payment-api's RedisRateLimiterUnitTest): EVALSHA is the primary path
     * (skips resending the script body); a NOSCRIPT reply falls back to a plain EVAL for that same
     * call instead of denying the request that triggered the reload.
     */
    @Test
    void aNoScriptReplyFallsBackToEvalInsteadOfDenyingTheCurrentRequest() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.<Long>evalsha(anyString(), any(), any(String[].class), any(String[].class)))
                .thenThrow(new io.lettuce.core.RedisNoScriptException("NOSCRIPT"));
        when(commands.<Long>eval(anyString(), any(), any(String[].class), any(String[].class)))
                .thenReturn(1L);
        RedisRateLimiter limiter = new RedisRateLimiter(() -> commands, "core-command", 50, 1_000L);

        boolean admitted = limiter.tryAcquire();

        assertTrue(admitted, "a NOSCRIPT reply must not deny the current request");
        verify(commands).eval(anyString(), any(), any(String[].class), any(String[].class));
    }

    /**
     * T33/SCAL-06: the sliding-window estimate itself is evaluated inside the Lua script (only
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
        RedisRateLimiter limiter = new RedisRateLimiter(() -> commands, "core-command", 50, 1_000L);

        limiter.tryAcquire();

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

    @Test
    void theLimiterNameIsPartOfTheDistributedKeySoBudgetsDoNotCollide() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.<Long>evalsha(anyString(), any(), any(String[].class), any(String[].class)))
                .thenReturn(1L);
        RedisRateLimiter limiter = new RedisRateLimiter(() -> commands, "core-command", 50, 1_000L);

        limiter.tryAcquire();

        ArgumentCaptor<String[]> keys = ArgumentCaptor.forClass(String[].class);
        verify(commands).evalsha(anyString(), any(), keys.capture(), any(String[].class));
        assertTrue(keys.getValue()[0].startsWith("rl:core-command:"));
    }

    @Test
    void resultIsDeniedWhenTheScriptReturnsZero() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.<Long>evalsha(anyString(), any(), any(String[].class), any(String[].class)))
                .thenReturn(0L);
        RedisRateLimiter limiter = new RedisRateLimiter(() -> commands, "core-command", 50, 1_000L);

        assertTrue(!limiter.tryAcquire(), "a script result of 0 must deny the request");
        verify(commands, times(1)).evalsha(anyString(), any(), any(String[].class), any(String[].class));
    }
}
