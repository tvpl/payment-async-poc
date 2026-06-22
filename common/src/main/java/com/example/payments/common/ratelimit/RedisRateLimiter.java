package com.example.payments.common.ratelimit;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Distributed fixed-window rate limiter backed by Redis, so the limit is **global**
 * across all instances of a service (a per-instance Resilience4j limiter would allow
 * {@code N × limit} in aggregate). Atomicity is guaranteed by a small Lua script.
 *
 * <p>If Redis is unavailable, it degrades to a per-instance local fixed-window counter
 * (fail-degraded, not fail-open) so the Core/API still gets some protection.
 */
public class RedisRateLimiter {

    // INCR the window key; set TTL on first hit; allow while count <= limit.
    private static final String LUA = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if c <= tonumber(ARGV[2]) then return 1 else return 0 end
            """;

    private final Supplier<RedisCommands<String, String>> commands;
    private final String name;
    private final int limitForPeriod;
    private final long windowMillis;

    // Local fallback state (used only when Redis is down).
    private final Object localLock = new Object();
    private long localWindow = -1;
    private final AtomicInteger localCount = new AtomicInteger();

    public RedisRateLimiter(Supplier<RedisCommands<String, String>> commands,
                            String name, int limitForPeriod, long windowMillis) {
        this.commands = commands;
        this.name = name;
        this.limitForPeriod = limitForPeriod;
        this.windowMillis = windowMillis;
    }

    /** @return true if a permit was granted for the current window. */
    public boolean tryAcquire() {
        long window = System.currentTimeMillis() / windowMillis;
        String key = "rl:" + name + ":" + window;
        try {
            Long allowed = commands.get().eval(LUA, ScriptOutputType.INTEGER,
                    new String[]{key},
                    String.valueOf(windowMillis), String.valueOf(limitForPeriod));
            return allowed != null && allowed == 1L;
        } catch (Exception e) {
            return localTryAcquire(window);
        }
    }

    private boolean localTryAcquire(long window) {
        synchronized (localLock) {
            if (window != localWindow) {
                localWindow = window;
                localCount.set(0);
            }
            return localCount.incrementAndGet() <= limitForPeriod;
        }
    }
}
