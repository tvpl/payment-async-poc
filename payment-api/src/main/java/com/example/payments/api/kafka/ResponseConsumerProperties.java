package com.example.payments.api.kafka;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;

import java.time.Duration;

/** Bounded retry budget applied before a final event is dead-lettered (PAY-09). */
@ConfigurationProperties("payment.response-consumer")
public class ResponseConsumerProperties {

    /** Attempts to apply a decoded event before it is dead-lettered. */
    private int maxAttempts = 3;
    /** Pause between attempts. Bounded on purpose: this blocks the partition. */
    private Duration retryDelay = Duration.ofMillis(500);

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    @PostConstruct
    public void validate() {
        if (maxAttempts < 1) {
            throw new ConfigurationException("payment.response-consumer.max-attempts must be at least 1");
        }
        if (retryDelay == null || retryDelay.isNegative()) {
            throw new ConfigurationException("payment.response-consumer.retry-delay must not be negative");
        }
    }
}
