package com.example.payments.sbus.ratelimit;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.function.Supplier;

/** Distributed fixed-window guard for SBUS core-command publication. */
public final class RedisRateLimiter {

    private static final String LUA = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if c <= tonumber(ARGV[2]) then return 1 else return 0 end
            """;

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
        long window = System.currentTimeMillis() / windowMillis;
        String key = "rl:" + name + ":" + window;
        Long allowed = commands.get().eval(LUA, ScriptOutputType.INTEGER,
                new String[]{key}, String.valueOf(windowMillis), String.valueOf(limitForPeriod));
        return allowed != null && allowed == 1L;
    }
}
