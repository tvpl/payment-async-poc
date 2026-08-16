package com.example.payments.api.config;

import io.micronaut.configuration.kafka.config.AbstractKafkaProducerConfiguration;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.ProducerConfig;

/**
 * Applies the derived publish budget (BUDG-01) to the Kafka producer configuration at bean
 * creation - the same shape {@code KafkaProducerFactory} uses in payment-sbus: delivery.timeout.ms
 * carries the full budget, max.block.ms bounds how long a send call may block waiting for
 * metadata/buffer space, and request.timeout.ms is capped at 10s so a single in-flight request
 * cannot alone exhaust a larger budget.
 */
@Singleton
public class PublishBudgetProducerCustomizer implements BeanCreatedEventListener<AbstractKafkaProducerConfiguration> {

    private static final long REQUEST_TIMEOUT_CAP_MS = 10_000L;

    private final PublishBudgetProperties publishBudget;

    public PublishBudgetProducerCustomizer(PublishBudgetProperties publishBudget) {
        this.publishBudget = publishBudget;
    }

    @Override
    public AbstractKafkaProducerConfiguration onCreated(BeanCreatedEvent<AbstractKafkaProducerConfiguration> event) {
        AbstractKafkaProducerConfiguration configuration = event.getBean();
        long budgetMillis = publishBudget.getPublishBudget().toMillis();
        configuration.getConfig().put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Math.toIntExact(budgetMillis));
        configuration.getConfig().put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                Math.toIntExact(Math.min(budgetMillis, REQUEST_TIMEOUT_CAP_MS)));
        configuration.getConfig().put(ProducerConfig.MAX_BLOCK_MS_CONFIG, budgetMillis);
        return configuration;
    }
}
