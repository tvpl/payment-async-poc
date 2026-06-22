package com.example.payments.api.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.time.Duration;

/** Builds the admission {@link RateLimiter} used by the HTTP filter. */
@Factory
public class ApiRateLimiterFactory {

    @Singleton
    @Named("api-admission")
    public RateLimiter admissionRateLimiter(RateLimitProperties props) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(props.getLimitForPeriod())
                .limitRefreshPeriod(props.getRefreshPeriod())
                .timeoutDuration(Duration.ZERO) // non-blocking: reject immediately when full
                .build();
        return RateLimiter.of("api-admission", config);
    }
}
