package com.example.payments.sbus.kafka;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.support.KafkaHeaders;
import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.ErrorStrategyValue;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Reprocesses records from the dedicated retry topics. Honors {@code x-retry-not-before}
 * (bounded wait), re-dispatches via the shared handler, and either succeeds, schedules the
 * next attempt, or routes to the DLQ once attempts are exhausted. Runs in its own
 * consumer thread so retries never block the live (main-topic) partitions.
 */
@KafkaListener(
        groupId = "payment-sbus-retry",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.SYNC_PER_RECORD,
        errorStrategy = @ErrorStrategy(value = ErrorStrategyValue.RETRY_ON_ERROR, retryCount = 50, retryDelay = "2s"))
public class RetryConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(RetryConsumer.class);

    private final SimulationMessageHandler handler;
    private final RetryPublisher retryPublisher;
    private final RetryProperties properties;

    public RetryConsumer(SimulationMessageHandler handler,
                         RetryPublisher retryPublisher,
                         RetryProperties properties) {
        this.handler = handler;
        this.retryPublisher = retryPublisher;
        this.properties = properties;
    }

    @Topic({Topics.REQUESTED_RETRY, Topics.CORE_RESPONSE_RETRY})
    public void receive(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = KafkaHeaders.toMap(record);
        String originTopic = headers.getOrDefault(Headers.ORIGIN_TOPIC, Topics.REQUESTED);
        int attempt = parseInt(headers.get(Headers.RETRY_ATTEMPT), 1);

        waitUntilNotBefore(headers.get(Headers.RETRY_NOT_BEFORE));

        try {
            handler.handle(originTopic, record.value(), headers);
        } catch (PoisonMessageException poison) {
            retryPublisher.routeToDlq(originTopic, record.key(), record.value(), headers, poison, "poison");
        } catch (RuntimeException transientError) {
            boolean dlq = retryPublisher.scheduleNextOrDlq(
                    originTopic, record.key(), record.value(), headers, attempt, transientError);
            if (dlq) {
                LOG.error("Retry exhausted (attempt={}) origin={} key={} -> DLQ",
                        attempt, originTopic, record.key(), transientError);
            }
        }
    }

    /** Bounded wait so a record isn't reprocessed before its scheduled time. */
    private void waitUntilNotBefore(String notBeforeHeader) {
        long notBefore = parseLong(notBeforeHeader, 0L);
        long remaining = notBefore - System.currentTimeMillis();
        long capped = Math.min(remaining, properties.getMaxWait().toMillis());
        if (capped > 0) {
            try {
                Thread.sleep(capped);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
