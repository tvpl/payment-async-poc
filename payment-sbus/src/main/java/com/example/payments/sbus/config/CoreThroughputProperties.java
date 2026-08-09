package com.example.payments.sbus.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/** Throughput guard protecting the (slower) Core from request bursts. */
@ConfigurationProperties("sbus.core")
public class CoreThroughputProperties {

    /** Max core commands published per refresh period. */
    private int limitForPeriod = 50;
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
