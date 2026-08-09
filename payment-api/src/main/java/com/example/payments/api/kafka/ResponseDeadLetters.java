package com.example.payments.api.kafka;

import com.example.payments.api.metrics.ApiMetrics;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes an unusable final event to the dead-letter topic before its offset is committed.
 *
 * <p>The publish is synchronous and its failure is <strong>not</strong> swallowed: if the DLQ
 * cannot confirm the record, the caller rethrows and the offset stays uncommitted, so a
 * message is never acknowledged without landing somewhere recoverable (PAY-09).
 */
@Singleton
public class ResponseDeadLetters {

    static final String ORIGIN_TOPIC = "x-dlq-origin-topic";
    static final String STAGE = "x-dlq-stage";
    static final String REASON = "x-dlq-reason";

    private static final Logger LOG = LoggerFactory.getLogger(ResponseDeadLetters.class);

    private final PaymentResponseDlqProducer producer;
    private final ApiMetrics metrics;

    public ResponseDeadLetters(PaymentResponseDlqProducer producer, ApiMetrics metrics) {
        this.producer = producer;
        this.metrics = metrics;
    }

    public void route(ConsumerRecord<String, byte[]> record, String stage, Throwable cause) {
        String reason = cause == null ? stage : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        producer.send(record.key(), record.topic(), stage, reason, record.value());
        metrics.recordDeadLettered(stage);
        LOG.error("Dead-lettered final event topic={} partition={} offset={} stage={} reason={}",
                record.topic(), record.partition(), record.offset(), stage, reason);
    }
}
