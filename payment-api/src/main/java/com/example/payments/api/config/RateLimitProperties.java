package com.example.payments.api.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;

import java.time.Duration;

/**
 * Admission budgets for incoming simulation requests (returns 429 when exceeded).
 *
 * <p>Two budgets apply to every request: the resource budget caps the route as a whole, the
 * tenant budget stops one caller from consuming it. {@code instances} is what keeps the
 * degraded (Redis-down) budget honest — see {@code RedisRateLimiter} (CAP-03).
 */
@ConfigurationProperties("payment.simulation.rate-limit")
public class RateLimitProperties {

    /** Requests admitted per window for the route, across the fleet. */
    private int limitForPeriod = 200;
    /** Requests admitted per window for a single tenant, across the fleet. */
    private int tenantLimitForPeriod = 50;
    /** Instances sharing the budget. Divides the per-instance fallback when Redis is down. */
    private int instances = 1;
    private Duration refreshPeriod = Duration.ofSeconds(1);

    public int getLimitForPeriod() {
        return limitForPeriod;
    }

    public void setLimitForPeriod(int limitForPeriod) {
        this.limitForPeriod = limitForPeriod;
    }

    public int getTenantLimitForPeriod() {
        return tenantLimitForPeriod;
    }

    public void setTenantLimitForPeriod(int tenantLimitForPeriod) {
        this.tenantLimitForPeriod = tenantLimitForPeriod;
    }

    public int getInstances() {
        return instances;
    }

    public void setInstances(int instances) {
        this.instances = instances;
    }

    public Duration getRefreshPeriod() {
        return refreshPeriod;
    }

    public void setRefreshPeriod(Duration refreshPeriod) {
        this.refreshPeriod = refreshPeriod;
    }

    @PostConstruct
    public void validate() {
        if (limitForPeriod < 1) {
            throw new ConfigurationException(
                    "payment.simulation.rate-limit.limit-for-period must be at least 1");
        }
        if (tenantLimitForPeriod < 1) {
            throw new ConfigurationException(
                    "payment.simulation.rate-limit.tenant-limit-for-period must be at least 1");
        }
        if (tenantLimitForPeriod > limitForPeriod) {
            throw new ConfigurationException(
                    "payment.simulation.rate-limit.tenant-limit-for-period must not exceed "
                            + "limit-for-period, otherwise a single tenant could exceed the route budget");
        }
        if (instances < 1) {
            throw new ConfigurationException(
                    "payment.simulation.rate-limit.instances must be at least 1, "
                            + "otherwise the Redis-down budget would be unbounded per instance");
        }
        if (refreshPeriod == null || refreshPeriod.isNegative() || refreshPeriod.isZero()) {
            throw new ConfigurationException(
                    "payment.simulation.rate-limit.refresh-period must be positive");
        }
    }
}
