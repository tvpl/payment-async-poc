package com.example.payments.sbus.config;

import com.example.payments.sbus.ratelimit.RedisRateLimiter;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Builds the distributed {@link RedisRateLimiter} that throttles publication of
 * {@code core.command} — a <strong>global</strong> guard (across SBUS instances) that
 * protects a slower Core from bursts. Redis failure is fail-closed so capacity cannot
 * multiply with the number of SBUS instances.
 */
@Factory
public class RateLimiterFactory {

    @Singleton
    @Named("core-command")
    public RedisRateLimiter coreCommandLimiter(RedisCommandsProvider redis,
                                               CoreThroughputProperties props) {
        return new RedisRateLimiter(redis::commands, "core-command",
                props.getLimitForPeriod(), props.getRefreshPeriod().toMillis());
    }
}
