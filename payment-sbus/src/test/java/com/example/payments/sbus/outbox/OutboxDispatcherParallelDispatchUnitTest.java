package com.example.payments.sbus.outbox;

import com.example.payments.common.events.Topics;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.kafka.KafkaPublisher;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.ratelimit.RedisRateLimiter;
import com.example.payments.sbus.support.Json;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task_T38 (SCAL-02): deterministic, mock-driven proof of the per-row safety check parallel
 * dispatch relies on — {@code OutboxDispatcher} renews (and re-verifies) each row's OWN claim
 * immediately before its OWN send. Complements {@code OutboxBatchResilienceIT}'s real-concurrency
 * tests, which prove the same property under genuine parallelism but cannot pin down the exact
 * "renewal already lost before dispatch" timing without an explicit race; here it is pinned down
 * exactly by mocking {@link OutboxClaimService} directly, no Postgres or threads required.
 */
class OutboxDispatcherParallelDispatchUnitTest {

    @Test
    void aRowWhoseClaimCannotBeRenewedIsNeverSentToKafkaAndRecordsAFailureMetric() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        SbusMetrics metrics = mock(SbusMetrics.class);
        Json json = mock(Json.class);
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        OutboxPublicationLock lock = mock(OutboxPublicationLock.class);
        Tracer tracer = OpenTelemetry.noop().getTracer("test");

        OutboxEvent event = pendingEvent("stale-claim", Topics.REQUESTED);
        when(claims.claimBatch()).thenReturn(List.of(event));
        // Something else (a concurrent reaper cycle) already reclaimed this exact row by the
        // time THIS dispatcher instance gets to its own dispatch turn.
        when(claims.renewRemaining(List.of(event))).thenReturn(false);

        OutboxDispatcher dispatcher =
                new OutboxDispatcher(claims, publisher, metrics, json, limiter, lock, tracer);

        int published = dispatcher.dispatchBatch();

        assertEquals(0, published);
        verify(publisher, never()).send(any(), any(), any(), any());
        verify(claims, never()).markPublished(any());
        verify(metrics).recordPublishFailure();
    }

    @Test
    void aRowWhoseClaimIsSuccessfullyRenewedProceedsToSendNormally() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        SbusMetrics metrics = mock(SbusMetrics.class);
        Json json = mock(Json.class);
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        OutboxPublicationLock lock = mock(OutboxPublicationLock.class);
        Tracer tracer = OpenTelemetry.noop().getTracer("test");

        OutboxEvent event = pendingEvent("healthy-claim", Topics.COMPLETED);
        when(claims.claimBatch()).thenReturn(List.of(event));
        when(claims.renewRemaining(List.of(event))).thenReturn(true);
        when(lock.executeIfAcquired(eq(event.getId()), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<Boolean> action = invocation.getArgument(1);
            return java.util.Optional.of(action.get());
        });
        when(claims.markPublished(event)).thenReturn(true);
        when(json.fromJson(any(), eq(java.util.Map.class))).thenReturn(java.util.Map.of());

        OutboxDispatcher dispatcher =
                new OutboxDispatcher(claims, publisher, metrics, json, limiter, lock, tracer);

        int published = dispatcher.dispatchBatch();

        assertEquals(1, published);
        verify(publisher).send(eq(Topics.COMPLETED), eq("healthy-claim"), any(), any());
        verify(claims).markPublished(event);
        verify(metrics, never()).recordPublishFailure();
    }

    private static OutboxEvent pendingEvent(String identity, String topic) {
        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        event.setAggregateType("test");
        event.setAggregateId(identity);
        event.setEventType("test");
        event.setTopic(topic);
        event.setKey(identity);
        event.setPayload(new byte[]{1});
        event.setHeaders("{}");
        event.setStatus(OutboxStatus.IN_PROGRESS);
        event.setAttempts(0);
        event.setNextAttemptAt(Instant.now().minusSeconds(1));
        event.setDeduplicationKey(identity);
        event.setClaimToken(UUID.randomUUID());
        return event;
    }
}
