package com.example.payments.api.service;

import com.example.payments.api.client.SbusStatusResponse;
import com.example.payments.api.config.ApiProperties;
import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.coordination.SbusStatusGateway;
import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.error.IdempotencyConflictException;
import com.example.payments.api.error.PublishFailedException;
import com.example.payments.api.error.StoreUnavailableException;
import com.example.payments.api.idempotency.IdempotencyFingerprint;
import com.example.payments.api.idempotency.IdempotencyOutcome;
import com.example.payments.api.idempotency.PublishState;
import com.example.payments.api.kafka.PaymentRequestProducer;
import com.example.payments.api.metrics.ApiMetrics;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.model.SimulationStatus;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiPaymentServiceUnitTest {

    private RedisStatusStore store;
    private ResponseCoordinator coordinator;
    private PaymentRequestProducer producer;
    private AvroSerde avroSerde;
    private ApiMetrics metrics;
    private SbusStatusGateway sbusStatusGateway;
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
        sbusStatusGateway = mock(SbusStatusGateway.class);
        service = new ApiPaymentService(store, coordinator, producer, avroSerde, metrics, sbusStatusGateway);

        when(avroSerde.serialize(anyString(), any())).thenReturn(new byte[]{1});
        when(coordinator.register(anyString())).thenReturn(new CompletableFuture<>());
        when(store.reserve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyOutcome.Reserved());
    }

    @Test
    void returnsResultWhenResponseArrivesInTime() {
        when(coordinator.await(anyString(), any())).thenAnswer(inv -> {
            String requestId = inv.getArgument(0);
            return Optional.of(new StatusEntry(requestId, SimulationStatus.COMPLETED, null));
        });

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, null, "tenant-a");

        assertFalse(result.timedOut());
        assertFalse(result.duplicate());
        assertEquals(SimulationStatus.COMPLETED, result.entry().status());
        verify(metrics).recordRequest(anyString());
        verify(producer).send(anyString(), anyString(), anyString(), any(), any(), any());
    }

    /**
     * task_89c681c8: causationId was declared in logback.xml but never put into MDC. For the
     * first event in a chain it must equal the request's own requestId (EventEnvelope's own
     * javadoc convention) — captured here at the moment of publish, while MDC is still active.
     */
    @Test
    void populatesCausationIdInMdcWhilePublishing() {
        when(coordinator.await(anyString(), any())).thenReturn(Optional.empty());
        when(store.get(anyString())).thenReturn(Optional.empty());
        AtomicReference<String> causationIdDuringPublish = new AtomicReference<>();
        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        doAnswer(inv -> {
            causationIdDuringPublish.set(MDC.get("causationId"));
            return null;
        }).when(producer).send(requestId.capture(), anyString(), anyString(), any(), any(), any());

        service.submit(REQUEST, null, "tenant-a");

        assertEquals(requestId.getValue(), causationIdDuringPublish.get());
    }

    /** TEN-05: tenantId reaches the MDC (and therefore the logs) while publishing. */
    @Test
    void populatesTenantIdInMdcWhilePublishing() {
        when(coordinator.await(anyString(), any())).thenReturn(Optional.empty());
        when(store.get(anyString())).thenReturn(Optional.empty());
        AtomicReference<String> tenantIdDuringPublish = new AtomicReference<>();
        doAnswer(inv -> {
            tenantIdDuringPublish.set(MDC.get("tenantId"));
            return null;
        }).when(producer).send(anyString(), anyString(), anyString(), any(), any(), any());

        service.submit(REQUEST, null, "tenant-a");

        assertEquals("tenant-a", tenantIdDuringPublish.get());
    }

    /** OBS-03: a valid inbound x-correlation-id is adopted as-is instead of a fresh one. */
    @Test
    void adoptsAValidInboundCorrelationId() {
        when(coordinator.await(anyString(), any())).thenReturn(Optional.empty());
        when(store.get(anyString())).thenReturn(Optional.empty());

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, null, "tenant-a", "client-abcdefgh-1");

        assertEquals("client-abcdefgh-1", result.correlationId());
    }

    /**
     * OBS-03: a malformed inbound x-correlation-id is silently ignored (a fresh id is generated)
     * and never turned into a rejection - the submit still completes normally.
     */
    @Test
    void ignoresAMalformedInboundCorrelationIdAndNeverRejectsTheRequest() {
        when(coordinator.await(anyString(), any())).thenReturn(Optional.empty());
        when(store.get(anyString())).thenReturn(Optional.empty());

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, null, "tenant-a", "not valid!");

        assertFalse(result.correlationId().isBlank());
        assertTrue(result.correlationId().length() >= 8);
        assertNotEquals("not valid!", result.correlationId());
    }

    @Test
    void returns202WhenNoResponseInTime() {
        when(coordinator.await(anyString(), any())).thenReturn(Optional.empty());
        when(store.get(anyString())).thenAnswer(inv ->
                Optional.of(new StatusEntry(inv.getArgument(0), SimulationStatus.SENT_TO_SBUS, null)));

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, null, "tenant-a");

        assertTrue(result.timedOut());
        verify(metrics).recordTimeout();
    }

    @Test
    void readsAfterRegisterToCatchFastResponses() {
        // The fix for the "fast response lost the waiter" race: submit must poll the
        // store right after registering, before blocking on the future.
        when(coordinator.await(anyString(), any()))
                .thenAnswer(inv -> Optional.of(new StatusEntry(inv.getArgument(0), SimulationStatus.COMPLETED, null)));

        service.submit(REQUEST, null, "tenant-a");

        verify(coordinator).completeFromStore(anyString());
    }

    @Test
    void replaysOnDuplicateIdempotencyKey() {
        when(store.reserve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyOutcome.Replay("original-request-id"));
        when(store.get("original-request-id"))
                .thenReturn(Optional.of(new StatusEntry(
                        "original-request-id", SimulationStatus.COMPLETED, null)));

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, "the-key", "tenant-a");

        assertTrue(result.duplicate());
        assertFalse(result.timedOut());
        assertEquals("original-request-id", result.entry().requestId());
    }

    @Test
    void marksTheReservationPublishedOnlyAfterTheBrokerAcknowledges() {
        when(coordinator.await(anyString(), any()))
                .thenAnswer(inv -> Optional.of(new StatusEntry(inv.getArgument(0), SimulationStatus.COMPLETED, null)));

        service.submit(REQUEST, "the-key", "tenant-a");

        ArgumentCaptor<PublishState> state = ArgumentCaptor.forClass(PublishState.class);
        verify(store).markPublishState(anyString(), eq("the-key"), anyString(), anyString(), state.capture());
        assertEquals(PublishState.PUBLISHED, state.getValue());

        ArgumentCaptor<StatusEntry> saved = ArgumentCaptor.forClass(StatusEntry.class);
        verify(store, atLeastOnce()).save(saved.capture());
        assertEquals(SimulationStatus.SENT_TO_SBUS, saved.getAllValues().getLast().status());
    }

    @Test
    void marksTheReservationPublishFailedWhenTheBrokerRejectsTheSend() {
        doThrow(new RuntimeException("broker down"))
                .when(producer).send(anyString(), anyString(), anyString(), any(), any(), any());

        assertThrows(PublishFailedException.class, () -> service.submit(REQUEST, "the-key", "tenant-a"));

        ArgumentCaptor<PublishState> state = ArgumentCaptor.forClass(PublishState.class);
        verify(store).markPublishState(anyString(), eq("the-key"), anyString(), anyString(), state.capture());
        assertEquals(PublishState.PUBLISH_FAILED, state.getValue());

        ArgumentCaptor<StatusEntry> saved = ArgumentCaptor.forClass(StatusEntry.class);
        verify(store, atLeastOnce()).save(saved.capture());
        assertTrue(saved.getAllValues().stream()
                .noneMatch(entry -> entry.status() == SimulationStatus.SENT_TO_SBUS
                        || entry.status() == SimulationStatus.PROCESSING));
    }

    /**
     * task_T7 (AUD-06): only the publish-failure path used to unregister the waiter it
     * registered. Any exception thrown by markPublishState/save/completeFromStore between
     * register() and await() leaked the waiter forever - it never expires, so it is a
     * permanent entry in the pending-waiters map (and in {@code api_pending}). These three
     * tests use a real {@link ResponseCoordinator} (not a mock) so {@code pendingCount()}
     * reflects the actual waiter registry, the same way {@link ResponseCoordinatorUnitTest}
     * proves every other exit path leaves it empty.
     */
    @Test
    void unregistersTheWaiterWhenMarkPublishStateThrowsAfterRegister() {
        ResponseCoordinator realCoordinator = new ResponseCoordinator(mock(RedisClient.class), store, new ApiProperties());
        ApiPaymentService realService =
                new ApiPaymentService(store, realCoordinator, producer, avroSerde, metrics, sbusStatusGateway);
        doThrow(new StoreUnavailableException("Redis down", new RuntimeException()))
                .when(store).markPublishState(anyString(), anyString(), anyString(), anyString(), eq(PublishState.PUBLISHED));

        assertThrows(StoreUnavailableException.class, () -> realService.submit(REQUEST, "the-key", "tenant-a"));

        assertEquals(0, realCoordinator.pendingCount(),
                "the waiter must be unregistered even though markPublishState threw after register()");
    }

    @Test
    void unregistersTheWaiterWhenSaveThrowsAfterRegister() {
        ResponseCoordinator realCoordinator = new ResponseCoordinator(mock(RedisClient.class), store, new ApiProperties());
        ApiPaymentService realService =
                new ApiPaymentService(store, realCoordinator, producer, avroSerde, metrics, sbusStatusGateway);
        // Only the post-register save (SENT_TO_SBUS) should throw; the earlier PENDING save
        // (before register()) must be left alone or this would not exercise the leak at all.
        doThrow(new StoreUnavailableException("Redis down", new RuntimeException()))
                .when(store).save(argThat(entry -> entry.status() == SimulationStatus.SENT_TO_SBUS));

        assertThrows(StoreUnavailableException.class, () -> realService.submit(REQUEST, "the-key", "tenant-a"));

        assertEquals(0, realCoordinator.pendingCount(),
                "the waiter must be unregistered even though store.save(SENT_TO_SBUS) threw after register()");
    }

    @Test
    void unregistersTheWaiterWhenCompleteFromStoreThrowsAfterRegister() {
        ResponseCoordinator realCoordinator = new ResponseCoordinator(mock(RedisClient.class), store, new ApiProperties());
        ResponseCoordinator spyCoordinator = spy(realCoordinator);
        doThrow(new StoreUnavailableException("Redis down", new RuntimeException()))
                .when(spyCoordinator).completeFromStore(anyString());
        ApiPaymentService realService =
                new ApiPaymentService(store, spyCoordinator, producer, avroSerde, metrics, sbusStatusGateway);

        assertThrows(StoreUnavailableException.class, () -> realService.submit(REQUEST, "the-key", "tenant-a"));

        assertEquals(0, spyCoordinator.pendingCount(),
                "the waiter must be unregistered even though completeFromStore threw after register()");
    }

    @Test
    void resumesAnUnpublishedReservationUnderTheSameRequestId() {
        when(store.reserve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyOutcome.ResumePublish("original-request-id"));
        when(coordinator.await(anyString(), any()))
                .thenAnswer(inv -> Optional.of(new StatusEntry(inv.getArgument(0), SimulationStatus.COMPLETED, null)));

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, "the-key", "tenant-a");

        assertEquals("original-request-id", result.entry().requestId());
        verify(producer).send(eq("original-request-id"), eq("original-request-id"),
                anyString(), eq("the-key"), eq("tenant-a"), any());
        verify(store).markPublishState("tenant-a", "the-key", "original-request-id",
                IdempotencyFingerprint.of(REQUEST), PublishState.PUBLISHED);
    }

    @Test
    void replayWithoutAStoredStatusNeverReportsDownstreamProcessing() {
        when(store.reserve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyOutcome.Replay("original-request-id"));
        when(store.get("original-request-id")).thenReturn(Optional.empty());

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, "the-key", "tenant-a");

        assertEquals("original-request-id", result.entry().requestId());
        assertEquals(SimulationStatus.TIMEOUT, result.entry().status());
        verify(producer, never()).send(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void timeoutAfterAConfirmedPublishKeepsTheSameRequestId() {
        when(coordinator.await(anyString(), any())).thenReturn(Optional.empty());
        when(store.get(anyString())).thenReturn(Optional.empty());

        ApiPaymentService.SubmitResult result = service.submit(REQUEST, "the-key", "tenant-a");

        ArgumentCaptor<String> published = ArgumentCaptor.forClass(String.class);
        verify(producer).send(published.capture(), anyString(), anyString(), any(), any(), any());
        assertEquals(published.getValue(), result.entry().requestId());
        assertEquals(SimulationStatus.SENT_TO_SBUS, result.entry().status());
        assertTrue(result.timedOut());
    }

    @Test
    void leavesNoMdcBehindWhenTheResultArrives() {
        when(coordinator.await(anyString(), any()))
                .thenAnswer(inv -> Optional.of(new StatusEntry(inv.getArgument(0), SimulationStatus.COMPLETED, null)));

        service.submit(REQUEST, "the-key", "tenant-a");

        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("correlationId"));
        assertNull(MDC.get("causationId"));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("tenantId"));
    }

    @Test
    void leavesNoMdcBehindWhenTheWaitTimesOut() {
        when(coordinator.await(anyString(), any())).thenReturn(Optional.empty());
        when(store.get(anyString())).thenReturn(Optional.empty());

        service.submit(REQUEST, "the-key", "tenant-a");

        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("correlationId"));
        assertNull(MDC.get("causationId"));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("tenantId"));
    }

    @Test
    void leavesNoMdcBehindWhenThePublishFails() {
        doThrow(new RuntimeException("broker down"))
                .when(producer).send(anyString(), anyString(), anyString(), any(), any(), any());

        assertThrows(PublishFailedException.class, () -> service.submit(REQUEST, "the-key", "tenant-a"));

        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("correlationId"));
        assertNull(MDC.get("causationId"));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("tenantId"));
    }

    @Test
    void leavesNoMdcBehindWhenTheWaiterIsReleasedByShutdown() {
        when(coordinator.await(anyString(), any()))
                .thenThrow(new IllegalStateException("API shutting down"));

        assertThrows(IllegalStateException.class, () -> service.submit(REQUEST, "the-key", "tenant-a"));

        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("correlationId"));
        assertNull(MDC.get("causationId"));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("tenantId"));
    }

    @Test
    void rejectsDivergentPayloadOnSameIdempotencyKeyWithoutPublishing() {
        when(store.reserve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyOutcome.Conflict("original-request-id"));

        IdempotencyConflictException exception = assertThrows(IdempotencyConflictException.class,
                () -> service.submit(REQUEST, "the-key", "tenant-a"));

        assertEquals("the-key", exception.idempotencyKey());
        assertEquals("original-request-id", exception.originalRequestId());
        verify(producer, never()).send(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void getStatusFallsBackToSbusWhenRedisIsUnreachable() {
        String requestId = "req-outage-1";
        when(store.get(requestId)).thenThrow(new StoreUnavailableException("Failed to read status for " + requestId,
                new RuntimeException("connection refused")));
        when(sbusStatusGateway.getStatus(requestId)).thenReturn(Optional.of(
                new SbusStatusResponse(requestId, "COMPLETED", null)));

        Optional<StatusEntry> result = service.getStatus(requestId);

        assertTrue(result.isPresent());
        assertEquals(SimulationStatus.COMPLETED, result.get().status());
    }

    @Test
    void getStatusFailsClosedWhenRedisIsUnreachableAndSbusHasNoAnswer() {
        String requestId = "req-outage-2";
        when(store.get(requestId)).thenThrow(new StoreUnavailableException("Failed to read status for " + requestId,
                new RuntimeException("connection refused")));
        when(sbusStatusGateway.getStatus(requestId)).thenReturn(Optional.empty());

        assertThrows(StoreUnavailableException.class, () -> service.getStatus(requestId));
    }

    @Test
    void getStatusStillWorksNormallyWhenRedisIsHealthy() {
        String requestId = "req-healthy-1";
        when(store.get(requestId)).thenReturn(
                Optional.of(new StatusEntry(requestId, SimulationStatus.COMPLETED, null)));

        Optional<StatusEntry> result = service.getStatus(requestId);

        assertTrue(result.isPresent());
        assertEquals(SimulationStatus.COMPLETED, result.get().status());
        verify(sbusStatusGateway, never()).getStatus(anyString());
    }
}
