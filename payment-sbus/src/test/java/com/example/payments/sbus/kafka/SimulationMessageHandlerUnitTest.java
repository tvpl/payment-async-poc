package com.example.payments.sbus.kafka;

import com.example.payments.common.avro.CorePaymentSimulationResponse;
import com.example.payments.common.avro.CoreResponsePayload;
import com.example.payments.common.avro.PaymentRequest;
import com.example.payments.common.avro.PaymentSimulationRequested;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroCodecUnavailableException;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.sbus.service.PaymentSimulationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationMessageHandlerUnitTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void registryCapacityFailureRemainsRetryableOnTheKafkaRecord() {
        AvroSerde serde = mock(AvroSerde.class);
        byte[] payload = {1};
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(Topics.REQUESTED, 0, 0L, "key", payload);
        AvroCodecUnavailableException unavailable =
                new AvroCodecUnavailableException("registry codec unavailable");
        when(serde.deserialize(Topics.REQUESTED, payload)).thenThrow(unavailable);
        var handler = new SimulationMessageHandler(serde, mock(PaymentSimulationService.class));

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> handler.handle(Topics.REQUESTED, record));

        assertSame(unavailable, actual);
    }

    @Test
    void handlingARequestedEventPopulatesMdcWithTheEnvelopeCorrelationIdsWhileProcessing() {
        AvroSerde serde = mock(AvroSerde.class);
        byte[] payload = {1};
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(Topics.REQUESTED, 3, 77L, "key", payload);
        PaymentSimulationRequested avro = PaymentSimulationRequested.newBuilder()
                .setEventId("event-1")
                .setEventType("PaymentSimulationRequested")
                .setEventVersion("v1")
                .setOccurredAt(0L)
                .setRequestId("req-1")
                .setCorrelationId("corr-1")
                .setCausationId("cause-1")
                .setTraceId("trace-1")
                .setSource("sbus-test")
                .setPayload(PaymentRequest.newBuilder()
                        .setMerchantId("merchant-1")
                        .setAmount("10.00")
                        .setCurrency("BRL")
                        .setPaymentMethod("CREDIT_CARD")
                        .setBrand("VISA")
                        .setInstallments(1)
                        .setCaptureMode("AUTHORIZE_AND_CAPTURE")
                        .build())
                .build();
        when(serde.deserialize(Topics.REQUESTED, payload)).thenReturn(avro);
        PaymentSimulationService service = mock(PaymentSimulationService.class);
        AtomicReference<String> mdcRequestIdDuringCall = new AtomicReference<>();
        AtomicReference<String> mdcCorrelationIdDuringCall = new AtomicReference<>();
        AtomicReference<String> mdcCausationIdDuringCall = new AtomicReference<>();
        AtomicReference<String> mdcTraceIdDuringCall = new AtomicReference<>();
        AtomicReference<String> mdcTopicDuringCall = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcRequestIdDuringCall.set(MDC.get("requestId"));
            mdcCorrelationIdDuringCall.set(MDC.get("correlationId"));
            mdcCausationIdDuringCall.set(MDC.get("causationId"));
            mdcTraceIdDuringCall.set(MDC.get("traceId"));
            mdcTopicDuringCall.set(MDC.get("topic"));
            return null;
        }).when(service).handleRequested(any(), any(), any());
        var handler = new SimulationMessageHandler(serde, service);

        handler.handle(Topics.REQUESTED, record);

        assertEquals("req-1", mdcRequestIdDuringCall.get(),
                "MDC must carry the envelope's requestId while the handler is still processing");
        assertEquals("corr-1", mdcCorrelationIdDuringCall.get());
        assertEquals("cause-1", mdcCausationIdDuringCall.get());
        assertEquals("trace-1", mdcTraceIdDuringCall.get());
        assertEquals(Topics.REQUESTED, mdcTopicDuringCall.get());
        assertNull(MDC.get("requestId"), "MDC must be cleared once handling finishes");
    }

    @Test
    void handlingACoreResponseEventPopulatesMdcWithTheEnvelopeCorrelationIdsWhileProcessing() {
        AvroSerde serde = mock(AvroSerde.class);
        byte[] payload = {2};
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(Topics.CORE_RESPONSE, 1, 9L, "key", payload);
        CorePaymentSimulationResponse avro = CorePaymentSimulationResponse.newBuilder()
                .setEventId("event-2")
                .setEventType("CorePaymentSimulationResponse")
                .setEventVersion("v1")
                .setOccurredAt(0L)
                .setRequestId("req-2")
                .setCorrelationId("corr-2")
                .setCausationId("cause-2")
                .setTraceId("trace-2")
                .setSource("sbus-test")
                .setPayload(CoreResponsePayload.newBuilder()
                        .setSimulationId("sim-1")
                        .setStatus("APPROVED")
                        .setAmount("10.00")
                        .setCurrency("BRL")
                        .setInstallments(1)
                        .build())
                .build();
        when(serde.deserialize(Topics.CORE_RESPONSE, payload)).thenReturn(avro);
        PaymentSimulationService service = mock(PaymentSimulationService.class);
        AtomicReference<String> mdcCorrelationIdDuringCall = new AtomicReference<>();
        AtomicReference<String> mdcCausationIdDuringCall = new AtomicReference<>();
        AtomicReference<String> mdcTraceIdDuringCall = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcCorrelationIdDuringCall.set(MDC.get("correlationId"));
            mdcCausationIdDuringCall.set(MDC.get("causationId"));
            mdcTraceIdDuringCall.set(MDC.get("traceId"));
            return null;
        }).when(service).handleCoreResponse(any());
        var handler = new SimulationMessageHandler(serde, service);

        handler.handle(Topics.CORE_RESPONSE, record);

        assertEquals("corr-2", mdcCorrelationIdDuringCall.get(),
                "MDC must carry the envelope's correlationId while the core-response handler is still processing");
        assertEquals("cause-2", mdcCausationIdDuringCall.get());
        assertEquals("trace-2", mdcTraceIdDuringCall.get());
        assertNull(MDC.get("correlationId"), "MDC must be cleared once handling finishes");
    }
}
