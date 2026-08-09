package com.example.payments.sbus.kafka;

import com.example.payments.sbus.config.DependencyPolicies;
import com.example.payments.sbus.config.OutboxProperties;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * Provides a plain Kafka {@link Producer} so the {@code OutboxPublisher} can send
 * to a topic chosen at runtime (the topic is stored on each outbox row) and replay
 * the exact technical headers (including {@code traceparent}) captured at ingest.
 *
 * <p>{@code acks=all} + idempotent producer give us at-least-once with no
 * duplicates from producer retries; consumer-side idempotency handles the rest.
 */
@Factory
public class KafkaProducerFactory {

    @Bean(preDestroy = "close")
    @Primary
    @Singleton
    @Named("sbus-outbox")
    public Producer<String, byte[]> kafkaProducer(
            @Value("${kafka.bootstrap.servers:`localhost:9092`}") String bootstrapServers,
            OutboxProperties outbox,
            DependencyPolicies policies) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "sbus-outbox-publisher");
        long publishTimeoutMillis = Math.min(outbox.getPublishTimeout().toMillis(),
                policies.budget(DependencyPolicies.Dependency.KAFKA).timeout().toMillis());
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Math.toIntExact(publishTimeoutMillis));
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                Math.toIntExact(Math.min(publishTimeoutMillis, 10_000L)));
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, publishTimeoutMillis);
        props.put(ProducerConfig.RETRIES_CONFIG,
                policies.budget(DependencyPolicies.Dependency.KAFKA).maxAttempts() - 1);
        return new KafkaProducer<>(props);
    }
}
