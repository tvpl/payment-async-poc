package com.example.payments.sbus.kafka;

import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/** Synchronous Kafka send of raw bytes (Avro), used by the outbox dispatcher and DLQ. */
@Singleton
public class KafkaPublisher {

    private final Producer<String, byte[]> producer;

    public KafkaPublisher(Producer<String, byte[]> producer) {
        this.producer = producer;
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
        try {
            producer.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while publishing to " + topic, e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to publish to " + topic, e.getCause());
        }
    }
}
