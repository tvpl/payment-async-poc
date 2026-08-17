package com.example.payments.sbus.kafka;

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
 * Consumes the Core's response (Avro). Poison → DLQ; transient → retry topic.
 *
 * <p>{@code retryCount}/{@code retryDelay}: see {@link PaymentRequestedConsumer}'s javadoc —
 * same short (SCAL-01) budget against the same failure (Postgres down when {@link RetryPublisher}
 * tries to durably record the retry/DLQ).
 *
 * <p>{@code groupId} (AUD-10): dedicated to this topic, same reasoning as
 * {@link PaymentRequestedConsumer}'s javadoc — no longer shares {@code payment-sbus} with it, so
 * a rebalance on either listener never revokes the other's partitions. The new group's
 * {@code EARLIEST} reset rereads this topic's history once on first deploy — safe by
 * construction, since a Core response for a simulation that is already terminal (or unknown) is
 * ignored (see {@code PaymentSimulationService#handleCoreResponse}); proven directly by
 * {@code ConsumerGroupReplayIsInertIT}.
 *
 * <p>{@code threadsValue} (SCAL-03): see {@link PaymentRequestedConsumer}'s javadoc — same
 * reasoning, this group's own default of 3.
 */
@KafkaListener(
        groupId = "payment-sbus-core-response",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.SYNC_PER_RECORD,
        threadsValue = "${sbus.kafka.consumers.core-response.threads:3}",
        errorStrategy = @ErrorStrategy(value = ErrorStrategyValue.RETRY_ON_ERROR, retryCount = 4, retryDelay = "250ms"))
public class CoreResponseConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(CoreResponseConsumer.class);

    private final SimulationMessageHandler handler;
    private final RetryPublisher retryPublisher;

    public CoreResponseConsumer(SimulationMessageHandler handler, RetryPublisher retryPublisher) {
        this.handler = handler;
        this.retryPublisher = retryPublisher;
    }

    @Topic(Topics.CORE_RESPONSE)
    public void receive(ConsumerRecord<String, byte[]> record) {
        try {
            handler.handle(Topics.CORE_RESPONSE, record);
        } catch (PoisonMessageException poison) {
            Map<String, String> headers = KafkaHeaders.toMap(record);
            retryPublisher.routeToDlq(Topics.CORE_RESPONSE, record, headers, poison, "poison");
        } catch (RuntimeException transientError) {
            LOG.warn("Transient failure on core-response key={} -> retry topic", record.key(), transientError);
            Map<String, String> headers = KafkaHeaders.toMap(record);
            retryPublisher.scheduleFirstRetry(Topics.CORE_RESPONSE, record, headers, transientError);
        }
    }
}
