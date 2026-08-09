package com.example.payments.api.config;

import com.example.payments.api.ratelimit.RedisRateLimiter;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Builds the API's <strong>distributed</strong> admission limiters (global across instances,
 * via Redis) used by {@code ConcurrencyLimitFilter} to return 429 under bursts: one budget for
 * the resource as a whole and one per tenant. Both fall back to a per-instance share of their
 * own budget when Redis is unavailable.
 */
@Factory
public class ApiRateLimiterFactory {

    public static final String RESOURCE_LIMITER = "api-admission";
    public static final String TENANT_LIMITER = "api-tenant-admission";

    @Singleton
    @Named(RESOURCE_LIMITER)
    public RedisRateLimiter admissionRateLimiter(ApiRedisCommandsProvider redis,
                                                 RateLimitProperties props) {
        return new RedisRateLimiter(redis::commands, RESOURCE_LIMITER,
                props.getLimitForPeriod(), props.getInstances(), props.getRefreshPeriod().toMillis());
    }

    @Singleton
    @Named(TENANT_LIMITER)
    public RedisRateLimiter tenantRateLimiter(ApiRedisCommandsProvider redis,
                                              RateLimitProperties props) {
        return new RedisRateLimiter(redis::commands, TENANT_LIMITER,
                props.getTenantLimitForPeriod(), props.getInstances(),
                props.getRefreshPeriod().toMillis());
    }
}
