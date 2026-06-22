package com.example.payments.api.kafka;

import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.metrics.ApiMetrics;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.avro.PaymentSimulationCompleted;
import com.example.payments.common.avro.PaymentSimulationFailed;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.SimulationResult;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Consumes the final {@code PaymentSimulationCompleted}/{@code Failed} events (Avro),
 * persists the result in Redis and wakes any waiting HTTP request (locally and, via
 * Redis pub/sub, on other instances).
 *
 * <p>Unique consumer group per instance ({@code ${random.uuid}}) so every instance
 * sees every final event — we don't know which instance holds the blocked request.
 */
@KafkaListener(groupId = "payment-api-${random.uuid}", offsetReset = OffsetReset.LATEST)
public class PaymentResponseConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentResponseConsumer.class);

    private final RedisStatusStore store;
    private final ResponseCoordinator coordinator;
    private final AvroSerde avroSerde;
    private final ApiMetrics metrics;

    public PaymentResponseConsumer(RedisStatusStore store,
                                   ResponseCoordinator coordinator,
                                   AvroSerde avroSerde,
                                   ApiMetrics metrics) {
        this.store = store;
        this.coordinator = coordinator;
        this.avroSerde = avroSerde;
        this.metrics = metrics;
    }

    @Topic({Topics.COMPLETED, Topics.FAILED})
    public void receive(ConsumerRecord<String, byte[]> record) {
        EventEnvelope<SimulationResult> env;
        boolean completed;
        try {
            SpecificRecord avro = avroSerde.deserialize(record.topic(), record.value());
            if (avro instanceof PaymentSimulationCompleted c) {
                env = AvroMapper.fromAvro(c);
                completed = true;
            } else if (avro instanceof PaymentSimulationFailed f) {
                env = AvroMapper.fromAvro(f);
                completed = false;
            } else {
                LOG.error("Unexpected event type on {} offset={}", record.topic(), record.offset());
                return;
            }
        } catch (Exception e) {
            LOG.error("Failed to decode final event on {} offset={}", record.topic(), record.offset(), e);
            return;
        }

        SimulationStatus status = completed ? SimulationStatus.COMPLETED : SimulationStatus.FAILED;
        try {
            MDC.put("requestId", env.requestId());
            MDC.put("correlationId", env.correlationId());
            MDC.put("traceId", env.traceId());
            MDC.put("eventType", env.eventType());
            MDC.put("status", status.name());

            store.save(new StatusEntry(env.requestId(), status, env.payload()));
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
