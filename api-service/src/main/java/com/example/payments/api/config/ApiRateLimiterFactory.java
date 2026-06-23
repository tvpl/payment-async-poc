package com.example.payments.api.config;

import com.example.payments.common.ratelimit.RedisRateLimiter;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Builds the API's <strong>distributed</strong> admission rate limiter (global across
 * instances, via Redis) used by {@code ConcurrencyLimitFilter} to return 429 under bursts.
 * Falls back to a per-instance window if Redis is unavailable.
 */
@Factory
public class ApiRateLimiterFactory {

    @Singleton
    @Named("api-admission")
    public RedisRateLimiter admissionRateLimiter(ApiRedisCommandsProvider redis,
                                                 RateLimitProperties props) {
        return new RedisRateLimiter(redis::commands, "api-admission",
                props.getLimitForPeriod(), props.getRefreshPeriod().toMillis());
    }
}
