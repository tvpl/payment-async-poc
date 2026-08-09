package com.example.payments.sbus.kafka;

import com.example.payments.common.avro.CorePaymentSimulationResponse;
import com.example.payments.common.avro.PaymentSimulationRequested;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.sbus.service.PaymentSimulationService;
import jakarta.inject.Singleton;

import java.util.Map;

/**
 * Shared decode + route logic used by both the main consumers and the retry consumer.
 *
 * <p>Deserialization/validation failures are surfaced as {@link PoisonMessageException}
 * (route straight to DLQ); failures from the business processing propagate as ordinary
 * {@link RuntimeException} (eligible for retry).
 */
@Singleton
public class SimulationMessageHandler {

    private final AvroSerde avroSerde;
    private final PaymentSimulationService service;

    public SimulationMessageHandler(AvroSerde avroSerde, PaymentSimulationService service) {
        this.avroSerde = avroSerde;
        this.service = service;
    }

    public void handle(String originTopic, byte[] value, Map<String, String> headers) {
        switch (originTopic) {
            case Topics.REQUESTED -> handleRequested(value, headers);
            case Topics.CORE_RESPONSE -> handleCoreResponse(value);
            default -> throw new PoisonMessageException("Unknown origin topic: " + originTopic, null);
        }
    }

    private void handleRequested(byte[] value, Map<String, String> headers) {
        EventEnvelope<PaymentSimulationRequestPayload> env;
        try {
            PaymentSimulationRequested avro = avroSerde.deserialize(Topics.REQUESTED, value);
            env = AvroMapper.fromAvro(avro);
            if (env.requestId() == null || env.payload() == null
                    || env.payload().amount() == null || env.payload().merchantId() == null
                    || env.payload().currency() == null) {
                throw new IllegalArgumentException("missing required fields");
            }
        } catch (Exception e) {
            throw new PoisonMessageException("Invalid PaymentSimulationRequested", e);
        }
        // Business processing — transient failures propagate (retryable).
        service.handleRequested(env, headers.get(Headers.IDEMPOTENCY_KEY), headers.get(Headers.TRACEPARENT));
    }

    private void handleCoreResponse(byte[] value) {
        EventEnvelope<CorePaymentSimulationResponsePayload> env;
        try {
            CorePaymentSimulationResponse avro = avroSerde.deserialize(Topics.CORE_RESPONSE, value);
            env = AvroMapper.fromAvro(avro);
            if (env.payload() == null || env.payload().simulationId() == null) {
                throw new IllegalArgumentException("missing simulationId");
            }
        } catch (Exception e) {
            throw new PoisonMessageException("Invalid CorePaymentSimulationResponse", e);
        }
        service.handleCoreResponse(env);
    }
}
