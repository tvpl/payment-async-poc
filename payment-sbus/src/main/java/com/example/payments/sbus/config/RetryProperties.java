package com.example.payments.sbus.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/** Tunables for the dedicated retry topics. */
@ConfigurationProperties("sbus.retry")
public class RetryProperties {

    private int maxAttempts = 5;
    private Duration baseDelay = Duration.ofSeconds(1);
    private Duration maxDelay = Duration.ofSeconds(30);
    /** Cap on how long the retry consumer blocks waiting for a record's not-before. */
    private Duration maxWait = Duration.ofSeconds(5);

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBaseDelay() {
        return baseDelay;
    }

    public void setBaseDelay(Duration baseDelay) {
        this.baseDelay = baseDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public void setMaxDelay(Duration maxDelay) {
        this.maxDelay = maxDelay;
    }

    public Duration getMaxWait() {
        return maxWait;
    }

    public void setMaxWait(Duration maxWait) {
        this.maxWait = maxWait;
    }
}
