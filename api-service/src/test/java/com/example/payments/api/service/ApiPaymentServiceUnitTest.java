package com.example.payments.api.service;

import com.example.payments.api.client.SbusStatusClient;
import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.kafka.PaymentRequestProducer;
import com.example.payments.api.metrics.ApiMetrics;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.model.SimulationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiPaymentServiceUnitTest {

    private RedisStatusStore store;
    private ResponseCoordinator coordinator;
    private PaymentRequestProducer producer;
    private AvroSerde avroSerde;
    private ApiMetrics metrics;
    private SbusStatusClient sbusStatusClient;
    private ApiPaymentService service;

    private static final PaymentSimulationRequest REQUEST = new PaymentSimulationRequest(
            "MERCHANT-001", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");

    @BeforeEach
    void setUp() {
        store = mock(RedisStatusStore.class);
        coordinator = mock(ResponseCoordinator.class);
        producer = mock(PaymentRequestProducer.class);
        avroSerde = mock(AvroSerde.class);
        metrics = mock(ApiMetrics.class);
        sbusStatusClient = mock(SbusStatusClient.class);
        service = new ApiPaymentService(store, coordinator, producer, avroSerde, metrics, sbusStatusClient);

        when(avroSerde.serialize(anyString(), any())).thenReturn(new byte[]{1});
        when(coordinator.register(anyString())).thenReturn(new CompletableFuture<>());
        when(store.reserveIdempotency(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void returnsResultWhenResponseArrivesInTime() {
        when(coordinator.await(anyString(), any())).thenAnswer(inv -> {
            String requestId = inv.getArgument(0);
            return Optional.of(new StatusEntry(requestId, SimulationStatus.COMPLETED, null));
        });

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, null);

        assertFalse(result.timedOut());
        assertFalse(result.duplicate());
        assertEquals(SimulationStatus.COMPLETED, result.entry().status());
        verify(metrics).recordRequest(anyString());
        verify(producer).send(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void returns202WhenNoResponseInTime() {
        when(coordinator.await(anyString(), any())).thenReturn(Optional.empty());
        when(store.get(anyString())).thenAnswer(inv ->
                Optional.of(new StatusEntry(inv.getArgument(0), SimulationStatus.SENT_TO_SBUS, null)));

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, null);

        assertTrue(result.timedOut());
        verify(metrics).recordTimeout();
    }

    @Test
    void readsAfterRegisterToCatchFastResponses() {
        // The fix for the "fast response lost the waiter" race: submit must poll the
        // store right after registering, before blocking on the future.
        when(coordinator.await(anyString(), any()))
                .thenAnswer(inv -> Optional.of(new StatusEntry(inv.getArgument(0), SimulationStatus.COMPLETED, null)));

        service.submit(REQUEST, null);

        verify(coordinator).completeFromStore(anyString());
    }

    @Test
    void replaysOnDuplicateIdempotencyKey() {
        when(store.reserveIdempotency(anyString(), anyString()))
                .thenReturn(Optional.of("original-request-id"));
        when(store.get("original-request-id"))
                .thenReturn(Optional.of(new StatusEntry(
                        "original-request-id", SimulationStatus.COMPLETED, null)));

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, "the-key");

        assertTrue(result.duplicate());
        assertFalse(result.timedOut());
        assertEquals("original-request-id", result.entry().requestId());
    }
}
