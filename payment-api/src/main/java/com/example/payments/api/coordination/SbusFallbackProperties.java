package com.example.payments.api.coordination;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;

import java.time.Duration;

/** Failure policy for the durable status fallback (PAY-09). */
@ConfigurationProperties("payment.sbus")
public class SbusFallbackProperties {

    /** Consecutive failures that trip the circuit. */
    private int failureThreshold = 5;
    /** How long the circuit stays open before another call is attempted. */
    private Duration openDuration = Duration.ofSeconds(30);

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public Duration getOpenDuration() {
        return openDuration;
    }

    public void setOpenDuration(Duration openDuration) {
        this.openDuration = openDuration;
    }

    @PostConstruct
    public void validate() {
        if (failureThreshold < 1) {
            throw new ConfigurationException("payment.sbus.failure-threshold must be at least 1");
        }
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new ConfigurationException("payment.sbus.open-duration must be positive");
        }
    }
}
