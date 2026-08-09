package com.example.payments.api.ratelimit;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Distributed fixed-window limiter owned by the API boundary, scoped per resource or tenant.
 *
 * <p>When Redis answers, the window is global across instances. When it does not, the limiter
 * <strong>fails closed</strong> onto a per-instance share of the same budget
 * ({@code limitForPeriod / instances}, at least one) rather than the whole budget: a fleet of
 * N instances that each allowed the global limit would admit N× the approved burst exactly
 * when the coordination that bounds it is gone (CAP-03).
 */
public class RedisRateLimiter {

    private static final String LUA = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if c <= tonumber(ARGV[2]) then return 1 else return 0 end
            """;

    private final Supplier<RedisCommands<String, String>> commands;
    private final String name;
    private final int limitForPeriod;
    private final int degradedLimitForPeriod;
    private final long windowMillis;
    private final Map<String, LocalWindow> localWindows = new ConcurrentHashMap<>();

    public RedisRateLimiter(Supplier<RedisCommands<String, String>> commands,
                            String name, int limitForPeriod, int instances, long windowMillis) {
        this.commands = commands;
        this.name = name;
        this.limitForPeriod = limitForPeriod;
        this.degradedLimitForPeriod = Math.max(1, limitForPeriod / Math.max(1, instances));
        this.windowMillis = windowMillis;
    }

    /** Budget this instance allows on its own while Redis is unreachable. */
    public int degradedLimitForPeriod() {
        return degradedLimitForPeriod;
    }

    public boolean tryAcquire(String scope) {
        long window = System.currentTimeMillis() / windowMillis;
        String key = "rl:" + name + ":" + scope + ":" + window;
        try {
            Long allowed = commands.get().eval(LUA, ScriptOutputType.INTEGER,
                    new String[]{key}, String.valueOf(windowMillis), String.valueOf(limitForPeriod));
            return allowed != null && allowed == 1L;
        } catch (Exception exception) {
            return localTryAcquire(scope, window);
        }
    }

    private boolean localTryAcquire(String scope, long window) {
        LocalWindow local = localWindows.computeIfAbsent(scope, key -> new LocalWindow());
        synchronized (local) {
            if (window != local.window) {
                local.window = window;
                local.count = 0;
            }
            local.count++;
            return local.count <= degradedLimitForPeriod;
        }
    }

    private static final class LocalWindow {
        private long window = -1;
        private int count;
    }
}
