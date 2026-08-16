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
 * Consumes {@code PaymentSimulationRequested} (Avro). Decodes/validates via the shared
 * handler; on a poison message → DLQ; on a transient failure → the dedicated retry topic.
 * Offsets commit per record only after we return normally; if the DLQ/retry publish itself
 * fails we rethrow so {@link ErrorStrategy} retries the record (no silent loss).
 *
 * <p>The only way that rethrow happens is {@link RetryPublisher}'s own durable write failing —
 * in practice, Postgres itself being down. {@code retryCount}/{@code retryDelay} below are that
 * budget: 900 × 2s = 30 minutes, long enough to ride out a realistic failover or restart without
 * giving up. See {@link RetryPublisher} for what happens once even that is exhausted.
 *
 * <p>{@code groupId} (AUD-10) is dedicated to this topic — it used to share {@code payment-sbus}
 * with {@link CoreResponseConsumer}, so a rebalance triggered by either listener revoked
 * partition assignments on BOTH, even though they consume entirely different topics. Splitting
 * groups makes a rebalance on one never touch the other. The new group's {@code EARLIEST} offset
 * reset rereads this topic's full history exactly once on first deploy — safe by construction,
 * since {@code request_id UNIQUE} makes replaying an already-processed {@code Requested} record a
 * no-op (see {@code PaymentPersistenceService#persistRequested}); proven directly by
 * {@code ConsumerGroupReplayIsInertIT}.
 */
@KafkaListener(
        groupId = "payment-sbus-requested",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.SYNC_PER_RECORD,
        errorStrategy = @ErrorStrategy(value = ErrorStrategyValue.RETRY_ON_ERROR, retryCount = 900, retryDelay = "2s"))
public class PaymentRequestedConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentRequestedConsumer.class);

    private final SimulationMessageHandler handler;
    private final RetryPublisher retryPublisher;

    public PaymentRequestedConsumer(SimulationMessageHandler handler, RetryPublisher retryPublisher) {
        this.handler = handler;
        this.retryPublisher = retryPublisher;
    }

    @Topic(Topics.REQUESTED)
    public void receive(ConsumerRecord<String, byte[]> record) {
        try {
            handler.handle(Topics.REQUESTED, record);
        } catch (PoisonMessageException poison) {
            Map<String, String> headers = KafkaHeaders.toMap(record);
            retryPublisher.routeToDlq(Topics.REQUESTED, record, headers, poison, "poison");
        } catch (RuntimeException transientError) {
            LOG.warn("Transient failure on requested key={} -> retry topic", record.key(), transientError);
            Map<String, String> headers = KafkaHeaders.toMap(record);
            retryPublisher.scheduleFirstRetry(Topics.REQUESTED, record, headers, transientError);
        }
    }
}
