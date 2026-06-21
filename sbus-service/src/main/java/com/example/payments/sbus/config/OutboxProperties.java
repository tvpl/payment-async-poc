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
}
