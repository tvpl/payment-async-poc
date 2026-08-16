package com.example.payments.api.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/**
 * Publish budget for the Edge's Kafka producer (BUDG-01). {@link PublishBudgetProducerCustomizer}
 * derives {@code max.block.ms}, {@code request.timeout.ms} and {@code delivery.timeout.ms} from
 * this single value instead of the Kafka client's multi-tens-of-seconds defaults, and
 * {@link PublishBudgetGuard} checks it against {@code payment.simulation.wait-timeout} at boot.
 */
@ConfigurationProperties("payment")
public class PublishBudgetProperties {

    private Duration publishBudget = Duration.ofMillis(500);

    public Duration getPublishBudget() {
        return publishBudget;
    }

    public void setPublishBudget(Duration publishBudget) {
        this.publishBudget = publishBudget;
    }
}
