package com.example.payments.api.ratelimit;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Distributed sliding-window limiter owned by the API boundary, scoped per resource or tenant.
 *
 * <p>When Redis answers, the window is global across instances. When it does not, the limiter
 * <strong>fails closed</strong> onto a per-instance share of the same budget
 * ({@code limitForPeriod / instances}, at least one) rather than the whole budget: a fleet of
 * N instances that each allowed the global limit would admit N× the approved burst exactly
 * when the coordination that bounds it is gone (CAP-03).
 *
 * <p>SCAL-06: admission is evaluated by a weighted sliding-window approximation, not a bare
 * fixed window - a fixed window alone lets 2× the budget through in the instant spanning two
 * windows (the full budget right before the boundary, then the full budget again right after).
 * The estimate blends the current window's count with a decaying fraction of the previous
 * window's count ({@code current + previous * weightOfPreviousWindowStillInEffect}), so a
 * previous window that was already at budget leaves little to no room in the window that
 * follows it until enough of it has decayed away. Scripts are invoked with {@code EVALSHA}
 * (skips re-sending the script body on every call); a {@code NOSCRIPT} reply - the script is
 * not cached on this Redis node/after a restart - falls back to a plain {@code EVAL} for that
 * one call (which also re-caches the script under its SHA for the calls that follow), so a
 * cold cache never denies the request that triggered the reload.
 */
public class RedisRateLimiter {

    private static final String LUA = """
            local currentCount = redis.call('INCR', KEYS[1])
            if currentCount == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            local previousCount = tonumber(redis.call('GET', KEYS[2]) or '0')
            local weightPrevious = tonumber(ARGV[3])
            local estimated = currentCount + previousCount * weightPrevious
            if estimated > tonumber(ARGV[2]) then return 0 end
            return 1
            """;
    private static final String LUA_SHA = sha1Hex(LUA);

    /**
     * Evaluates the route (KEYS[1]/KEYS[2], ARGV[2]) and tenant (KEYS[3]/KEYS[4], ARGV[3])
     * sliding-window budgets in one round-trip. The route counter is incremented first; if the
     * route itself is already over budget, its own INCR above the limit is harmless (the tenant
     * counter is never touched). Only when the route admits does the tenant counter get
     * evaluated - and if the tenant denies, the route token just consumed is rolled back with a
     * DECR before returning 0, so a tenant that is over its own budget never eats into the
     * shared route budget (AUD-05).
     */
    private static final String DUAL_BUDGET_LUA = """
            local routeCurrent = redis.call('INCR', KEYS[1])
            if routeCurrent == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            local routePrevious = tonumber(redis.call('GET', KEYS[2]) or '0')
            local weightPrevious = tonumber(ARGV[4])
            local routeEstimated = routeCurrent + routePrevious * weightPrevious
            if routeEstimated > tonumber(ARGV[2]) then return 0 end
            local tenantCurrent = redis.call('INCR', KEYS[3])
            if tenantCurrent == 1 then redis.call('PEXPIRE', KEYS[3], ARGV[1]) end
            local tenantPrevious = tonumber(redis.call('GET', KEYS[4]) or '0')
            local tenantEstimated = tenantCurrent + tenantPrevious * weightPrevious
            if tenantEstimated > tonumber(ARGV[3]) then
              redis.call('DECR', KEYS[1])
              return 0
            end
            return 1
            """;
    private static final String DUAL_BUDGET_LUA_SHA = sha1Hex(DUAL_BUDGET_LUA);

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
        long now = System.currentTimeMillis();
        long window = now / windowMillis;
        double weightPrevious = weightOfPreviousWindow(now);
        String currentKey = key(scope, window);
        String previousKey = key(scope, window - 1);
        try {
            Long allowed = evalScript(LUA, LUA_SHA,
                    new String[]{currentKey, previousKey},
                    String.valueOf(windowMillis), String.valueOf(limitForPeriod), String.valueOf(weightPrevious));
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
        long now = System.currentTimeMillis();
        long window = now / windowMillis;
        double weightPrevious = weightOfPreviousWindow(now);
        String routeCurrentKey = key(scope, window);
        String routePreviousKey = key(scope, window - 1);
        String tenantCurrentKey = tenantLimiter.key(tenantScope, window);
        String tenantPreviousKey = tenantLimiter.key(tenantScope, window - 1);
        try {
            Long allowed = evalScript(DUAL_BUDGET_LUA, DUAL_BUDGET_LUA_SHA,
                    new String[]{routeCurrentKey, routePreviousKey, tenantCurrentKey, tenantPreviousKey},
                    String.valueOf(windowMillis),
                    String.valueOf(limitForPeriod),
                    String.valueOf(tenantLimiter.limitForPeriod),
                    String.valueOf(weightPrevious));
            return allowed != null && allowed == 1L;
        } catch (Exception exception) {
            return localTryAcquire(scope, window) && tenantLimiter.localTryAcquire(tenantScope, window);
        }
    }

    /** EVALSHA first; a NOSCRIPT reply falls back to EVAL, which also re-caches the script. */
    private Long evalScript(String script, String sha, String[] keys, String... args) {
        try {
            return commands.get().evalsha(sha, ScriptOutputType.INTEGER, keys, args);
        } catch (RedisNoScriptException notLoaded) {
            return commands.get().eval(script, ScriptOutputType.INTEGER, keys, args);
        }
    }

    /**
     * Fraction of the previous window still weighted into the estimate, decaying linearly from
     * 1.0 right at the start of the current window to 0.0 right at its end - the SCAL-06
     * sliding-window approximation.
     */
    private double weightOfPreviousWindow(long now) {
        long elapsed = Math.floorMod(now, windowMillis);
        return (double) (windowMillis - elapsed) / windowMillis;
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

    private static String sha1Hex(String script) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(script.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static final class LocalWindow {
        private long window = -1;
        private int count;
    }
}
