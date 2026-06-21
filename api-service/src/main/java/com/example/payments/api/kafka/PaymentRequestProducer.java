package com.example.payments.api.kafka;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.messaging.annotation.MessageHeader;

/**
 * Publishes {@code PaymentSimulationRequested}. Keyed by requestId for per-request
 * ordering. {@code traceparent} is injected automatically by Micronaut's
 * OpenTelemetry Kafka instrumentation, so trace context flows into the SBUS.
 */
@KafkaClient(id = "payment-request-producer", acks = KafkaClient.Acknowledge.ALL)
public interface PaymentRequestProducer {

    @Topic(Topics.REQUESTED)
    void send(@KafkaKey String requestId,
              @MessageHeader(Headers.REQUEST_ID) String reqId,
              @MessageHeader(Headers.CORRELATION_ID) String correlationId,
              @MessageHeader(Headers.IDEMPOTENCY_KEY) @Nullable String idempotencyKey,
              String value);
}
