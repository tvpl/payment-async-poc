package com.example.payments.sbus.kafka;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.outbox.BackoffCalculator;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Routes failed records to the dedicated retry topics (or the DLQ once attempts are
 * exhausted). Retries happen off the main partition so they don't block live traffic.
 */
@Singleton
public class RetryPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(RetryPublisher.class);

    private final KafkaPublisher publisher;
    private final RetryProperties properties;
    private final SbusMetrics metrics;

    public RetryPublisher(KafkaPublisher publisher, RetryProperties properties, SbusMetrics metrics) {
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** First failure on the main topic → schedule attempt #1 on the retry topic. */
    public void scheduleFirstRetry(String originTopic, String key, byte[] value,
                                   Map<String, String> headers, Throwable cause) {
        publishRetry(originTopic, key, value, headers, 1, cause);
    }

    /**
     * Subsequent failure on the retry topic → next attempt, or DLQ once exhausted.
     * @return true if routed to DLQ (terminal)
     */
    public boolean scheduleNextOrDlq(String originTopic, String key, byte[] value,
                                     Map<String, String> headers, int currentAttempt, Throwable cause) {
        if (currentAttempt >= properties.getMaxAttempts()) {
            routeToDlq(originTopic, key, value, headers, cause, "retries-exhausted");
            return true;
        }
        publishRetry(originTopic, key, value, headers, currentAttempt + 1, cause);
        return false;
    }

    private void publishRetry(String originTopic, String key, byte[] value,
                              Map<String, String> headers, int attempt, Throwable cause) {
        Duration delay = BackoffCalculator.backoff(attempt, properties.getBaseDelay(), properties.getMaxDelay());
        Map<String, String> h = new HashMap<>(headers);
        h.put(Headers.ORIGIN_TOPIC, originTopic);
        h.put(Headers.RETRY_ATTEMPT, String.valueOf(attempt));
        h.put(Headers.RETRY_NOT_BEFORE, String.valueOf(System.currentTimeMillis() + delay.toMillis()));
        h.put("x-retry-reason", String.valueOf(cause.getMessage()));
        publisher.send(retryTopicFor(originTopic), key, value, h);
        LOG.warn("Scheduled retry attempt={} origin={} key={} in {}ms", attempt, originTopic, key, delay.toMillis());
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

    private static String retryTopicFor(String originTopic) {
        return switch (originTopic) {
            case Topics.REQUESTED -> Topics.REQUESTED_RETRY;
            case Topics.CORE_RESPONSE -> Topics.CORE_RESPONSE_RETRY;
            default -> Topics.DLQ;
        };
    }
}
