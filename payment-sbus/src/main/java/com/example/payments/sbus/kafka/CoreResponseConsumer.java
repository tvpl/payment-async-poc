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

/** Consumes the Core's response (Avro). Poison → DLQ; transient → retry topic. */
@KafkaListener(
        groupId = "payment-sbus",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.SYNC_PER_RECORD,
        errorStrategy = @ErrorStrategy(value = ErrorStrategyValue.RETRY_ON_ERROR, retryCount = 50, retryDelay = "2s"))
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
        Map<String, String> headers = KafkaHeaders.toMap(record);
        try {
            handler.handle(Topics.CORE_RESPONSE, record.value(), headers);
        } catch (PoisonMessageException poison) {
            retryPublisher.routeToDlq(Topics.CORE_RESPONSE, record.key(), record.value(), headers, poison, "poison");
        } catch (RuntimeException transientError) {
            LOG.warn("Transient failure on core-response key={} -> retry topic", record.key(), transientError);
            retryPublisher.scheduleFirstRetry(Topics.CORE_RESPONSE, record.key(), record.value(), headers, transientError);
        }
    }
}
