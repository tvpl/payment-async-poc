package com.example.payments.sbus.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/** Tunables for the outbox publisher loop. */
@ConfigurationProperties("sbus.outbox")
public class OutboxProperties {

    private int batchSize = 100;
    private int maxAttempts = 8;
    private Duration baseBackoff = Duration.ofSeconds(2);
    private Duration maxBackoff = Duration.ofMinutes(5);
    /** IN_PROGRESS rows older than this are reclaimed to PENDING (publish crashed). */
    private Duration lease = Duration.ofMinutes(1);
    /** PUBLISHED rows older than this are purged by housekeeping. */
    private Duration retention = Duration.ofDays(3);

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBaseBackoff() {
        return baseBackoff;
    }

    public void setBaseBackoff(Duration baseBackoff) {
        this.baseBackoff = baseBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public Duration getLease() {
        return lease;
    }

    public void setLease(Duration lease) {
        this.lease = lease;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }
}
