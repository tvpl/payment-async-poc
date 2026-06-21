package com.example.payments.api.kafka;

import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.metrics.ApiMetrics;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Topics;
import com.example.payments.common.model.SimulationResult;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.core.type.Argument;
import io.micronaut.serde.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Consumes the final {@code PaymentSimulationCompleted}/{@code Failed} events,
 * persists the result in Redis and wakes any waiting HTTP request (locally and,
 * via Redis pub/sub, on other instances).
 *
 * <p>Uses a unique consumer group per instance ({@code ${random.uuid}}) so every
 * instance sees every final event — required because we don't know which instance
 * holds the blocked request.
 */
@KafkaListener(groupId = "payment-api-${random.uuid}", offsetReset = OffsetReset.LATEST)
public class PaymentResponseConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentResponseConsumer.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Argument<EventEnvelope<SimulationResult>> TYPE =
            (Argument) Argument.of(EventEnvelope.class, SimulationResult.class);

    private final RedisStatusStore store;
    private final ResponseCoordinator coordinator;
    private final ObjectMapper objectMapper;
    private final ApiMetrics metrics;

    public PaymentResponseConsumer(RedisStatusStore store,
                                   ResponseCoordinator coordinator,
                                   ObjectMapper objectMapper,
                                   ApiMetrics metrics) {
        this.store = store;
        this.coordinator = coordinator;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Topic({Topics.COMPLETED, Topics.FAILED})
    public void receive(ConsumerRecord<String, String> record) throws Exception {
        EventEnvelope<SimulationResult> env = objectMapper.readValue(record.value(), TYPE);
        boolean completed = EventTypes.PAYMENT_SIMULATION_COMPLETED.equals(env.eventType());
        SimulationStatus status = completed ? SimulationStatus.COMPLETED : SimulationStatus.FAILED;

        try {
            MDC.put("requestId", env.requestId());
            MDC.put("correlationId", env.correlationId());
            MDC.put("traceId", env.traceId());
            MDC.put("eventType", env.eventType());
            MDC.put("status", status.name());

            store.save(new StatusEntry(env.requestId(), status, env.payload()));
            // Wake local waiter immediately, and notify all instances via pub/sub.
            coordinator.complete(env.requestId());
            store.publishResponse(env.requestId());

            if (completed) {
                metrics.recordCompleted();
            } else {
                metrics.recordFailed();
            }
            LOG.info("Final event applied requestId={} status={}", env.requestId(), status);
        } finally {
            MDC.clear();
        }
    }
}
