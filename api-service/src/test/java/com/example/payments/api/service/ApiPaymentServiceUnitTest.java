package com.example.payments.api.service;

import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.kafka.PaymentRequestProducer;
import com.example.payments.api.metrics.ApiMetrics;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.serde.ObjectMapper;
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
    private ObjectMapper objectMapper;
    private ApiMetrics metrics;
    private ApiPaymentService service;

    private static final PaymentSimulationRequest REQUEST = new PaymentSimulationRequest(
            "MERCHANT-001", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");

    @BeforeEach
    void setUp() throws Exception {
        store = mock(RedisStatusStore.class);
        coordinator = mock(ResponseCoordinator.class);
        producer = mock(PaymentRequestProducer.class);
        objectMapper = mock(ObjectMapper.class);
        metrics = mock(ApiMetrics.class);
        service = new ApiPaymentService(store, coordinator, producer, objectMapper, metrics);

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
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
        verify(metrics).recordRequest();
        verify(producer).send(anyString(), anyString(), anyString(), any(), anyString());
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
