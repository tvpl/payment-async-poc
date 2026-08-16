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
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /**
     * task_T14 (AUD-09): a registry CONNECTIVITY failure (network I/O) during decode used to be
     * classified poison and dead-lettered — a perfectly valid payment lost to a routine registry
     * restart. It must instead surface as {@link RegistryUnavailableException} and flow through
     * the same retryable path {@code AvroCodecUnavailableException} already does.
     */
    @Test
    void registryConnectivityFailureDuringDecodeIsRetryableNotPoison() {
        AvroSerde serde = mock(AvroSerde.class);
        byte[] payload = {1};
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(Topics.REQUESTED, 0, 0L, "key", payload);
        // Mirrors how the Apicurio client actually fails when the registry is unreachable: a
        // RuntimeException wrapping the underlying IOException (see JdkHttpClient/ExceptionMapper).
        RuntimeException connectivityFailure =
                new RuntimeException("registry unreachable", new java.net.ConnectException("Connection refused"));
        when(serde.deserialize(Topics.REQUESTED, payload)).thenThrow(connectivityFailure);
        var handler = new SimulationMessageHandler(serde, mock(PaymentSimulationService.class));

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> handler.handle(Topics.REQUESTED, record));

        assertTrue(actual instanceof RegistryUnavailableException,
                "a registry connectivity failure must not be classified poison: " + actual);
        assertSame(connectivityFailure, actual.getCause());
    }

    /**
     * task_T14 (AUD-09): the Apicurio client does NOT preserve the underlying IOException as a
     * Java cause for a connectivity failure — {@code ErrorHandler#parseError} (in the real
     * client) synthesizes a fresh {@code RestClientException} from just the failing exception's
     * class name and message, discarding the original exception entirely. Its own signal for
     * "never got an HTTP response at all" is an embedded {@code Error} with no {@code errorCode}
     * — a real HTTP error response from the registry always carries one. This is the actual
     * shape {@code DependencyReadinessIT} observes against a real stopped registry container.
     */
    @Test
    void registryRestClientExceptionWithNoErrorCodeIsRetryableNotPoison() {
        AvroSerde serde = mock(AvroSerde.class);
        byte[] payload = {1};
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(Topics.REQUESTED, 0, 0L, "key", payload);
        var noResponseError = new io.apicurio.registry.rest.v2.beans.Error();
        noResponseError.setName("ConnectException");
        noResponseError.setMessage("Connection refused");
        var connectivityFailure =
                new io.apicurio.registry.rest.client.exception.RestClientException(noResponseError);
        when(serde.deserialize(Topics.REQUESTED, payload)).thenThrow(connectivityFailure);
        var handler = new SimulationMessageHandler(serde, mock(PaymentSimulationService.class));

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> handler.handle(Topics.REQUESTED, record));

        assertTrue(actual instanceof RegistryUnavailableException,
                "an errorCode-less RestClientException (no HTTP response received) must not be classified poison: "
                        + actual);
    }

    /**
     * task_T14 (AUD-09) regression guard: a REAL HTTP error response from the registry (a
     * genuine schema/artifact problem, e.g. 404 not found) always carries an errorCode — that is
     * NOT a connectivity failure and must still poison, or a truly broken payload would retry
     * forever instead of going to the DLQ.
     */
    @Test
    void registryRestClientExceptionWithAnErrorCodeStillPoisons() {
        AvroSerde serde = mock(AvroSerde.class);
        byte[] payload = {1};
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(Topics.REQUESTED, 0, 0L, "key", payload);
        var realHttpError = new io.apicurio.registry.rest.v2.beans.Error();
        realHttpError.setName("ArtifactNotFoundException");
        realHttpError.setMessage("Schema not found");
        realHttpError.setErrorCode(404);
        var schemaFailure =
                new io.apicurio.registry.rest.client.exception.RestClientException(realHttpError);
        when(serde.deserialize(Topics.REQUESTED, payload)).thenThrow(schemaFailure);
        var handler = new SimulationMessageHandler(serde, mock(PaymentSimulationService.class));

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> handler.handle(Topics.REQUESTED, record));

        assertTrue(actual instanceof PoisonMessageException,
                "a real HTTP error response from the registry (errorCode present) is not a connectivity "
                        + "failure and must still poison: " + actual);
    }

    /**
     * task_T14 (AUD-09) regression guard: a genuinely undecodable payload never reaches the
     * network (a malformed wire header fails locally), so it must still be classified poison and
     * routed to the DLQ — the connectivity carve-out above must not swallow real poison messages.
     */
    @Test
    void genuinelyUndecodablePayloadWithNoNetworkFailureInItsChainStillPoisons() {
        AvroSerde serde = mock(AvroSerde.class);
        byte[] payload = {9, 9, 9};
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(Topics.REQUESTED, 0, 0L, "key", payload);
        IllegalStateException malformed = new IllegalStateException("unknown magic byte");
        when(serde.deserialize(Topics.REQUESTED, payload)).thenThrow(malformed);
        var handler = new SimulationMessageHandler(serde, mock(PaymentSimulationService.class));

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> handler.handle(Topics.REQUESTED, record));

        assertTrue(actual instanceof PoisonMessageException,
                "a payload decode failure with no connectivity cause in its chain must poison: " + actual);
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
                .setEventVersion("1.0")
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
                .setEventVersion("1.0")
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
