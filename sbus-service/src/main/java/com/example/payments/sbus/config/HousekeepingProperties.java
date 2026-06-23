package com.example.payments.sbus.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/** Retention windows for the housekeeping jobs (keep tables bounded). */
@ConfigurationProperties("sbus.housekeeping")
public class HousekeepingProperties {

    private Duration idempotencyRetention = Duration.ofDays(7);
    private Duration messageRetention = Duration.ofDays(30);
    private int batchSize = 500;

    public Duration getIdempotencyRetention() {
        return idempotencyRetention;
    }

    public void setIdempotencyRetention(Duration idempotencyRetention) {
        this.idempotencyRetention = idempotencyRetention;
    }

    public Duration getMessageRetention() {
        return messageRetention;
    }

    public void setMessageRetention(Duration messageRetention) {
        this.messageRetention = messageRetention;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
