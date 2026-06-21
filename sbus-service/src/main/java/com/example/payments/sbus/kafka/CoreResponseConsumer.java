package com.example.payments.sbus.kafka;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.Topics;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.service.PaymentSimulationService;
import com.example.payments.sbus.support.Json;
import com.example.payments.sbus.support.Mdc;
import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.ErrorStrategyValue;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.core.type.Argument;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/** Consumes the Core's response and triggers the final completed/failed outbox event. */
@KafkaListener(
        groupId = "payment-sbus",
        offsetReset = OffsetReset.EARLIEST,
        errorStrategy = @ErrorStrategy(
                value = ErrorStrategyValue.RETRY_ON_ERROR,
                retryCount = 3,
                retryDelay = "1s"))
public class CoreResponseConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(CoreResponseConsumer.class);
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Argument<EventEnvelope<CorePaymentSimulationResponsePayload>> TYPE =
            (Argument) Argument.of(EventEnvelope.class, CorePaymentSimulationResponsePayload.class);

    private final PaymentSimulationService service;
    private final Json json;
    private final KafkaPublisher publisher;
    private final SbusMetrics metrics;

    public CoreResponseConsumer(PaymentSimulationService service,
                                Json json,
                                KafkaPublisher publisher,
                                SbusMetrics metrics) {
        this.service = service;
        this.json = json;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @Topic(Topics.CORE_RESPONSE)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope<CorePaymentSimulationResponsePayload> env;
        try {
            env = json.fromJson(record.value(), TYPE);
            if (env == null || env.payload() == null || env.payload().simulationId() == null) {
                throw new PoisonMessageException("Invalid Core response envelope", null);
            }
        } catch (Exception parseError) {
            LOG.error("Routing poison CoreResponse to DLQ offset={}", record.offset(), parseError);
            Map<String, String> headers = new HashMap<>();
            headers.put("x-dlq-origin-topic", record.topic());
            headers.put("x-dlq-reason", parseError.getMessage());
            publisher.send(Topics.DLQ, record.key(), record.value(), headers);
            metrics.recordDlq();
            return;
        }
        try {
            Mdc.fromConsumer(record, env);
            service.handleCoreResponse(env);
        } finally {
            Mdc.clear();
        }
    }
}
