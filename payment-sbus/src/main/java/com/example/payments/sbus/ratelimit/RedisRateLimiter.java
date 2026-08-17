package com.example.payments.sbus.ratelimit;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;

/**
 * Distributed fixed-window guard for SBUS core-command publication.
 *
 * <p>SCAL-06 (mirrors {@code payment-api}'s {@code RedisRateLimiter}): admission is evaluated by
 * a weighted sliding-window approximation, not a bare fixed window — a fixed window alone lets 2x
 * the budget through in the instant spanning two windows (the full budget right before the
 * boundary, then the full budget again right after). The estimate blends the current window's
 * count with a decaying fraction of the previous window's count. Scripts are invoked with
 * {@code EVALSHA} (skips re-sending the script body on every call); a {@code NOSCRIPT} reply -
 * the script is not cached on this Redis node / after a restart - falls back to a plain
 * {@code EVAL} for that one call (which also re-caches the script under its SHA), so a cold cache
 * never denies the request that triggered the reload.
 */
public final class RedisRateLimiter {

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

    private final Supplier<RedisCommands<String, String>> commands;
    private final String name;
    private final int limitForPeriod;
    private final long windowMillis;

    public RedisRateLimiter(Supplier<RedisCommands<String, String>> commands,
                            String name, int limitForPeriod, long windowMillis) {
        this.commands = commands;
        this.name = name;
        this.limitForPeriod = limitForPeriod;
        this.windowMillis = windowMillis;
    }

    public boolean tryAcquire() {
        long now = System.currentTimeMillis();
        long window = now / windowMillis;
        double weightPrevious = weightOfPreviousWindow(now);
        String currentKey = key(window);
        String previousKey = key(window - 1);
        Long allowed = evalScript(new String[]{currentKey, previousKey},
                String.valueOf(windowMillis), String.valueOf(limitForPeriod), String.valueOf(weightPrevious));
        return allowed != null && allowed == 1L;
    }

    /** EVALSHA first; a NOSCRIPT reply falls back to EVAL, which also re-caches the script. */
    private Long evalScript(String[] keys, String... args) {
        try {
            return commands.get().evalsha(LUA_SHA, ScriptOutputType.INTEGER, keys, args);
        } catch (RedisNoScriptException notLoaded) {
            return commands.get().eval(LUA, ScriptOutputType.INTEGER, keys, args);
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

    private String key(long window) {
        return "rl:" + name + ":" + window;
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
}
