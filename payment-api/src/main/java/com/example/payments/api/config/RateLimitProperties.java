package com.example.payments.api.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/** Admission rate limit for incoming simulation requests (returns 429 when exceeded). */
@ConfigurationProperties("payment.simulation.rate-limit")
public class RateLimitProperties {

    private int limitForPeriod = 200;
    private Duration refreshPeriod = Duration.ofSeconds(1);

    public int getLimitForPeriod() {
        return limitForPeriod;
    }

    public void setLimitForPeriod(int limitForPeriod) {
        this.limitForPeriod = limitForPeriod;
    }

    public Duration getRefreshPeriod() {
        return refreshPeriod;
    }

    public void setRefreshPeriod(Duration refreshPeriod) {
        this.refreshPeriod = refreshPeriod;
    }
}
