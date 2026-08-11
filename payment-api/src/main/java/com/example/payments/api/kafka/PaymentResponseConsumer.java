package com.example.payments.api.kafka;

import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.metrics.ApiMetrics;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.avro.PaymentSimulationCompleted;
import com.example.payments.common.avro.PaymentSimulationFailed;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroCodecUnavailableException;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.SimulationResult;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.ErrorStrategyValue;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Optional;

/**
 * Consumes the final {@code PaymentSimulationCompleted}/{@code Failed} events (Avro),
 * persists the result in Redis and wakes any waiting HTTP request (locally and, via
 * Redis pub/sub, on other instances).
 *
 * <p>Single, stable consumer group ({@code payment-api}): one instance consumes each
 * partition, writes the result to Redis and publishes the requestId on the Redis pub/sub
 * channel; <em>every</em> instance is subscribed and wakes its local waiter. This avoids
 * the orphan-consumer-group buildup (a random group id per restart leaked groups forever)
 * and the N× redundant processing of a per-instance group.
 *
 * <p>Nothing is acknowledged silently (PAY-09). Offsets commit per record only after this
 * method returns normally. A message we can never decode goes to the DLQ with its original
 * bytes and a reason; a message we merely failed to apply (Redis unavailable) is retried
 * within a bounded budget and then dead-lettered. Codec-capacity failures are neither: they
 * are transient, so the record is rethrown for redelivery rather than discarded.
 */
@KafkaListener(
        groupId = "payment-api",
        offsetReset = OffsetReset.LATEST,
        offsetStrategy = OffsetStrategy.SYNC_PER_RECORD,
        errorStrategy = @ErrorStrategy(value = ErrorStrategyValue.RETRY_ON_ERROR,
                retryCount = 10, retryDelay = "2s"))
public class PaymentResponseConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentResponseConsumer.class);

    static final String STAGE_DECODE = "decode";
    static final String STAGE_APPLY = "apply";

    private final RedisStatusStore store;
    private final ResponseCoordinator coordinator;
    private final AvroSerde avroSerde;
    private final ApiMetrics metrics;
    private final ResponseDeadLetters deadLetters;
    private final ResponseConsumerProperties properties;

    public PaymentResponseConsumer(RedisStatusStore store,
                                   ResponseCoordinator coordinator,
                                   AvroSerde avroSerde,
                                   ApiMetrics metrics,
                                   ResponseDeadLetters deadLetters,
                                   ResponseConsumerProperties properties) {
        this.store = store;
        this.coordinator = coordinator;
        this.avroSerde = avroSerde;
        this.metrics = metrics;
        this.deadLetters = deadLetters;
        this.properties = properties;
    }

    private record FinalEvent(EventEnvelope<SimulationResult> envelope, boolean completed) {
    }

    @Topic({Topics.COMPLETED, Topics.FAILED})
    public void receive(ConsumerRecord<String, byte[]> record) {
        FinalEvent event;
        try {
            event = decode(record);
        } catch (AvroCodecUnavailableException capacity) {
            // Capacity, not content: the message is fine, we are momentarily out of codecs.
            // Rethrow so the record is redelivered instead of burning a valid result.
            throw capacity;
        } catch (RuntimeException poison) {
            deadLetters.route(record, STAGE_DECODE, poison);
            return;
        }
        applyWithinBudget(event, record);
    }

    private FinalEvent decode(ConsumerRecord<String, byte[]> record) {
        SpecificRecord avro = avroSerde.deserialize(record.topic(), record.value());
        if (avro instanceof PaymentSimulationCompleted completed) {
            return new FinalEvent(AvroMapper.fromAvro(completed), true);
        }
        if (avro instanceof PaymentSimulationFailed failed) {
            return new FinalEvent(AvroMapper.fromAvro(failed), false);
        }
        throw new IllegalArgumentException(
                "Unexpected event type on " + record.topic() + ": " + avro.getClass().getName());
    }

    private void applyWithinBudget(FinalEvent event, ConsumerRecord<String, byte[]> record) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                apply(event);
                return;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                metrics.recordResponseRetry();
                LOG.warn("Failed to apply final event requestId={} attempt={}/{}: {}",
                        event.envelope().requestId(), attempt, properties.getMaxAttempts(),
                        failure.getMessage());
                pauseBeforeRetry(attempt);
            }
        }
        deadLetters.route(record, STAGE_APPLY, lastFailure);
    }

    private void pauseBeforeRetry(int attempt) {
        if (attempt >= properties.getMaxAttempts() || properties.getRetryDelay().isZero()) {
            return;
        }
        try {
            Thread.sleep(properties.getRetryDelay().toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a final event", interrupted);
        }
    }

    private void apply(FinalEvent event) {
        EventEnvelope<SimulationResult> env = event.envelope();
        SimulationStatus status = event.completed() ? SimulationStatus.COMPLETED : SimulationStatus.FAILED;
        try {
            MDC.put("requestId", env.requestId());
            MDC.put("correlationId", env.correlationId());
            MDC.put("causationId", env.causationId());
            MDC.put("traceId", env.traceId());
            MDC.put("eventType", env.eventType());
            MDC.put("status", status.name());

            Optional<StatusEntry> existing = store.get(env.requestId());
            if (existing.isPresent() && isTerminal(existing.get().status())) {
                // The outcome was already chosen. A repeat — redelivery, or a republish after a
                // crash between the Kafka ack and the outbox mark — must not change it (PAY-06).
                metrics.recordDuplicateFinalEvent();
                coordinator.complete(env.requestId());
                store.publishResponse(env.requestId());
                LOG.info("Duplicate final event ignored requestId={} keptStatus={} repeatedStatus={}",
                        env.requestId(), existing.get().status(), status);
                return;
            }

            store.save(new StatusEntry(env.requestId(), status, env.payload()));
            coordinator.complete(env.requestId());
            store.publishResponse(env.requestId());

            if (event.completed()) {
                metrics.recordCompleted();
            } else {
                metrics.recordFailed();
            }
            LOG.info("Final event applied requestId={} status={}", env.requestId(), status);
        } finally {
            MDC.clear();
        }
    }

    private static boolean isTerminal(SimulationStatus status) {
        return status == SimulationStatus.COMPLETED || status == SimulationStatus.FAILED;
    }
}
