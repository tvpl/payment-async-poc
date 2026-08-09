package com.example.payments.sbus.kafka;

import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.retry.DurableRetryScheduler;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Persists failed records for due-based retry (or sends to DLQ once attempts are exhausted).
 */
@Singleton
public class RetryPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(RetryPublisher.class);

    private final KafkaPublisher publisher;
    private final DurableRetryScheduler scheduler;
    private final RetryProperties properties;
    private final SbusMetrics metrics;

    public RetryPublisher(KafkaPublisher publisher,
                          DurableRetryScheduler scheduler,
                          RetryProperties properties,
                          SbusMetrics metrics) {
        this.publisher = publisher;
        this.scheduler = scheduler;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** First failure on the main topic → schedule attempt #1 on the retry topic. */
    public void scheduleFirstRetry(String originTopic, ConsumerRecord<String, byte[]> source,
                                   Map<String, String> headers, Throwable cause) {
        scheduler.schedule(originTopic, source, headers, 1, cause);
    }

    /**
     * Subsequent failure on the retry topic → next attempt, or DLQ once exhausted.
     * @return true if routed to DLQ (terminal)
     */
    public boolean scheduleNextOrDlq(String originTopic, ConsumerRecord<String, byte[]> source,
                                     Map<String, String> headers, int currentAttempt, Throwable cause) {
        if (currentAttempt >= properties.getMaxAttempts()) {
            routeToDlq(originTopic, source.key(), source.value(), headers, cause, "retries-exhausted");
            return true;
        }
        scheduler.schedule(originTopic, source, headers, currentAttempt + 1, cause);
        return false;
    }

    public void routeToDlq(String originTopic, String key, byte[] value,
                           Map<String, String> headers, Throwable cause, String stage) {
        Map<String, String> h = new HashMap<>(headers);
        h.put("x-dlq-origin-topic", originTopic);
        h.put("x-dlq-stage", stage);
        h.put("x-dlq-reason", String.valueOf(cause == null ? stage : cause.getMessage()));
        publisher.send(Topics.DLQ, key, value, h);
        metrics.recordDlq();
        LOG.error("Routed to DLQ origin={} key={} stage={}", originTopic, key, stage, cause);
    }
}
