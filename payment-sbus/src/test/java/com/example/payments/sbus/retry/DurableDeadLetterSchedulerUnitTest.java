package com.example.payments.sbus.retry;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.support.Json;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableDeadLetterSchedulerUnitTest {

    private OutboxEventRepository repository;
    private Json json;
    private DurableDeadLetterScheduler scheduler;
    private ConsumerRecord<String, byte[]> source;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        json = mock(Json.class);
        scheduler = new DurableDeadLetterScheduler(repository, json);
        source = new ConsumerRecord<>(Topics.REQUESTED, 3, 41L, "request-41", new byte[]{4, 1});
    }

    @Test
    void persistsRawRecordAndDlqMetadataBeforeReturning() {
        when(json.toJson(any())).thenReturn("{\"headers\":true}");
        when(repository.insertDurableDeadLetter(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        var result = scheduler.schedule(Topics.REQUESTED, source,
                Map.of(Headers.TRACEPARENT, "00-trace"), new RuntimeException("invalid"), "poison");

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(repository).insertDurableDeadLetter(eq("request-41"), eq("request-41"), bytes.capture(),
                eq("{\"headers\":true}"), any(Instant.class),
                eq("dlq:payment.simulation.requested:3:41:poison"));
        assertArrayEquals(source.value(), bytes.getValue());
        assertTrue(result.inserted());
    }

    @Test
    void augmentsHeadersWithoutDiscardingTraceContext() {
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        when(json.toJson(headers.capture())).thenReturn("{}");

        scheduler.schedule(Topics.REQUESTED, source, Map.of(Headers.TRACEPARENT, "trace"),
                new IllegalArgumentException("bad schema"), "decode");

        assertEquals("trace", headers.getValue().get(Headers.TRACEPARENT));
        assertEquals(Topics.REQUESTED, headers.getValue().get("x-dlq-origin-topic"));
        assertEquals("decode", headers.getValue().get("x-dlq-stage"));
        assertEquals("bad schema", headers.getValue().get("x-dlq-reason"));
    }

    @Test
    void duplicateScheduleReportsExistingDurableIdentity() {
        when(json.toJson(any())).thenReturn("{}");
        when(repository.insertDurableDeadLetter(any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        var result = scheduler.schedule(Topics.REQUESTED, source, Map.of(), null, "poison");

        assertFalse(result.inserted());
        assertEquals("dlq:payment.simulation.requested:3:41:poison", result.deduplicationKey());
    }

    @Test
    void storageFailurePropagatesSoConsumerCannotCommitOffset() {
        when(json.toJson(any())).thenReturn("{}");
        when(repository.insertDurableDeadLetter(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class,
                () -> scheduler.schedule(Topics.REQUESTED, source, Map.of(), null, "poison"));
    }

    @Test
    void stageSeparatesPoisonFromRetryExhaustionIdentity() {
        assertEquals("dlq:payment.simulation.requested:3:41:poison",
                DurableDeadLetterScheduler.deduplicationKey(source, "poison"));
        assertEquals("dlq:payment.simulation.requested:3:41:retries-exhausted",
                DurableDeadLetterScheduler.deduplicationKey(source, "retries-exhausted"));
    }

    @Test
    void unkeyedPoisonRecordGetsStableStorageAndDlqKey() {
        ConsumerRecord<String, byte[]> unkeyed =
                new ConsumerRecord<>(Topics.REQUESTED, 4, 51L, null, new byte[]{5, 1});
        when(json.toJson(any())).thenReturn("{}");

        scheduler.schedule(Topics.REQUESTED, unkeyed, Map.of(), null, "poison");

        verify(repository).insertDurableDeadLetter(
                eq("payment.simulation.requested:4:51"),
                eq("payment.simulation.requested:4:51"), any(), eq("{}"), any(),
                eq("dlq:payment.simulation.requested:4:51:poison"));
    }
}
