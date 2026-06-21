package com.example.payments.sbus.kafka;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
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

    @Singleton
    public Producer<String, String> kafkaProducer(
            @Value("${kafka.bootstrap.servers:localhost:9092}") String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "sbus-outbox-publisher");
        return new KafkaProducer<>(props);
    }
}
