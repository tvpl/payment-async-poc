package com.example.payments.sbus.kafka;

import com.example.payments.sbus.config.OutboxProperties;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Synchronous Kafka send of raw bytes (Avro), used by the outbox dispatcher and DLQ. */
@Singleton
public class KafkaPublisher {

    private final Producer<String, byte[]> producer;
    private final OutboxProperties properties;

    public KafkaPublisher(@Named("sbus-outbox") Producer<String, byte[]> producer,
                          OutboxProperties properties) {
        this.producer = producer;
        this.properties = properties;
    }

    /** Sends and blocks until the broker acknowledges, surfacing any failure. */
    public void send(String topic, String key, byte[] payload, Map<String, String> headers) {
        RecordHeaders recordHeaders = new RecordHeaders();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (v != null) {
                    recordHeaders.add(k, v.getBytes(StandardCharsets.UTF_8));
                }
            });
        }
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(topic, null, key, payload, recordHeaders);
        Future<RecordMetadata> delivery = producer.send(record);
        try {
            delivery.get(properties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing to " + topic, e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to publish to " + topic, e.getCause());
        } catch (TimeoutException e) {
            delivery.cancel(true);
            throw new RuntimeException("Timed out publishing to " + topic + " after "
                    + properties.getPublishTimeout(), e);
        }
    }
}
