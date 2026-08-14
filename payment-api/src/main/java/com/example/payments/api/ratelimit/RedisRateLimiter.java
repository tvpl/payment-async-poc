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

    /**
     * Evaluates the route (KEYS[1]/ARGV[2]) and tenant (KEYS[2]/ARGV[3]) budgets in one
     * round-trip. The route counter is incremented first; if the route itself is already over
     * budget, its own INCR above the limit is harmless (the tenant counter is never touched).
     * Only when the route admits does the tenant counter get evaluated - and if the tenant
     * denies, the route token just consumed is rolled back with a DECR before returning 0, so a
     * tenant that is over its own budget never eats into the shared route budget (AUD-05).
     */
    private static final String DUAL_BUDGET_LUA = """
            local r = redis.call('INCR', KEYS[1])
            if r == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if r > tonumber(ARGV[2]) then return 0 end
            local t = redis.call('INCR', KEYS[2])
            if t == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[1]) end
            if t > tonumber(ARGV[3]) then
              redis.call('DECR', KEYS[1])
              return 0
            end
            return 1
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
        String key = key(scope, window);
        try {
            Long allowed = commands.get().eval(LUA, ScriptOutputType.INTEGER,
                    new String[]{key}, String.valueOf(windowMillis), String.valueOf(limitForPeriod));
            return allowed != null && allowed == 1L;
        } catch (Exception exception) {
            return localTryAcquire(scope, window);
        }
    }

    /**
     * Atomically evaluates this limiter (the route budget) together with {@code tenantLimiter}
     * (the tenant budget) for the same request, with the tenant denial rolling back the route
     * token it would otherwise have wasted (AUD-05). When Redis is unreachable, both budgets
     * fall back independently to their own per-instance local slice, same as {@link #tryAcquire}.
     */
    public boolean tryAcquireBoth(String scope, RedisRateLimiter tenantLimiter, String tenantScope) {
        long window = System.currentTimeMillis() / windowMillis;
        String routeKey = key(scope, window);
        String tenantKey = tenantLimiter.key(tenantScope, window);
        try {
            Long allowed = commands.get().eval(DUAL_BUDGET_LUA, ScriptOutputType.INTEGER,
                    new String[]{routeKey, tenantKey},
                    String.valueOf(windowMillis),
                    String.valueOf(limitForPeriod),
                    String.valueOf(tenantLimiter.limitForPeriod));
            return allowed != null && allowed == 1L;
        } catch (Exception exception) {
            return localTryAcquire(scope, window) && tenantLimiter.localTryAcquire(tenantScope, window);
        }
    }

    private String key(String scope, long window) {
        return "rl:" + name + ":" + scope + ":" + window;
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
