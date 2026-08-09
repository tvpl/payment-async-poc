package com.example.payments.sbus.kafka;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
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
 * Reprocesses records published by the durable scheduler only after they are due.
 * The consumer never sleeps on a partition; another failure is durably rescheduled.
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
    public RetryConsumer(SimulationMessageHandler handler,
                         RetryPublisher retryPublisher) {
        this.handler = handler;
        this.retryPublisher = retryPublisher;
    }

    @Topic({Topics.REQUESTED_RETRY, Topics.CORE_RESPONSE_RETRY})
    public void receive(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = KafkaHeaders.toMap(record);
        String originTopic = headers.getOrDefault(Headers.ORIGIN_TOPIC, Topics.REQUESTED);
        int attempt = parseInt(headers.get(Headers.RETRY_ATTEMPT), 1);

        try {
            handler.handle(originTopic, record.value(), headers);
        } catch (PoisonMessageException poison) {
            retryPublisher.routeToDlq(originTopic, record, headers, poison, "poison");
        } catch (RuntimeException transientError) {
            boolean dlq = retryPublisher.scheduleNextOrDlq(
                    originTopic, record, headers, attempt, transientError);
            if (dlq) {
                LOG.error("Retry exhausted (attempt={}) origin={} key={} -> DLQ",
                        attempt, originTopic, record.key(), transientError);
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

}
