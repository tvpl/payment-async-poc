package com.example.payments.sbus.config;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.time.Duration;

/** Builds the Resilience4j {@link RateLimiterRegistry} with the {@code core-command} limiter. */
@Factory
public class RateLimiterFactory {

    @Singleton
    public RateLimiterRegistry rateLimiterRegistry(CoreThroughputProperties props) {
        RateLimiterConfig coreCommand = RateLimiterConfig.custom()
                .limitForPeriod(props.getLimitForPeriod())
                .limitRefreshPeriod(props.getRefreshPeriod())
                // Non-blocking: acquirePermission() returns immediately true/false.
                .timeoutDuration(Duration.ZERO)
                .build();
        RateLimiterRegistry registry = RateLimiterRegistry.ofDefaults();
        registry.rateLimiter("core-command", coreCommand);
        return registry;
    }
}
