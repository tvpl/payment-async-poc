package com.example.platform.asyncredis.ratelimit;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.lettuce.core.ScriptOutputType;
import jakarta.inject.Singleton;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distributed fixed-window admission limiter for {@code POST /jobs}, so a burst can't overwhelm the
 * workers regardless of how many API instances are running (a per-instance limiter would allow
 * {@code N × limit}). Same Lua-atomic technique as the platform's {@code RedisRateLimiter}, but
 * reimplemented here so this example stays standalone (no dependency on the Kafka/Avro {@code common}
 * module). Disabled when {@code admission-limit-per-sec <= 0}. Degrades to a local counter if Redis
 * is down (fail-degraded, not fail-open).
 */
@Singleton
public class AsyncRateLimiter {

    private static final String LUA = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if c <= tonumber(ARGV[2]) then return 1 else return 0 end
            """;

    private final RedisConnections redis;
    private final int limit;
    private final long windowMillis = 1000;

    private final Object localLock = new Object();
    private long localWindow = -1;
    private final AtomicInteger localCount = new AtomicInteger();

    public AsyncRateLimiter(RedisConnections redis, AsyncRedisProperties props) {
        this.redis = redis;
        this.limit = props.getAdmissionLimitPerSec();
    }

    /** @return true if the request may proceed. Always true when the limiter is disabled. */
    public boolean tryAcquire() {
        if (limit <= 0) {
            return true;
        }
        long window = System.currentTimeMillis() / windowMillis;
        String key = "async:rl:" + window;
        try {
            Long allowed = redis.shared().eval(LUA, ScriptOutputType.INTEGER,
                    new String[]{key}, Long.toString(windowMillis), Integer.toString(limit));
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
            return localCount.incrementAndGet() <= limit;
        }
    }
}
