package com.example.payments.sbus.retry;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.support.Json;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableRetrySchedulerUnitTest {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private OutboxEventRepository repository;
    private Json json;
    private RetryProperties properties;
    private DurableRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        json = mock(Json.class);
        properties = new RetryProperties();
        properties.setBaseDelay(Duration.ofSeconds(2));
        properties.setMaxDelay(Duration.ofSeconds(10));
        scheduler = new DurableRetryScheduler(repository, properties, json,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.insertDurableRetry(anyString(), anyString(), anyString(),
                any(byte[].class), anyString(), any(Instant.class), anyString())).thenReturn(1);
        when(json.toJson(any())).thenReturn("{\"persisted\":true}");
    }

    @Test
    void schedulesRequestedAttemptOneAtBaseDelay() {
        var result = scheduler.schedule(Topics.REQUESTED, record(Topics.REQUESTED, 1, 4),
                Map.of(), 1, new RuntimeException("db"));

        assertTrue(result.inserted());
        assertEquals(1, result.attempt());
        assertEquals(NOW.plusSeconds(2), result.dueAt());
        verify(repository).insertDurableRetry(eq("request-1"), eq(Topics.REQUESTED_RETRY),
                eq("request-1"), any(byte[].class), eq("{\"persisted\":true}"),
                eq(NOW.plusSeconds(2)), eq("retry:payment.simulation.requested:1:4:1"));
    }

    @Test
    void mapsCoreResponseToItsDedicatedRetryTopic() {
        scheduler.schedule(Topics.CORE_RESPONSE, record(Topics.CORE_RESPONSE, 0, 9),
                Map.of(), 1, new RuntimeException("db"));

        verify(repository).insertDurableRetry(anyString(), eq(Topics.CORE_RESPONSE_RETRY),
                anyString(), any(byte[].class), anyString(), any(Instant.class), anyString());
    }

    @Test
    void preservesKeyAndRawBytes() {
        byte[] bytes = new byte[]{7, 8, 9};
        ConsumerRecord<String, byte[]> source =
                new ConsumerRecord<>(Topics.REQUESTED, 3, 12L, "request-raw", bytes);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);

        scheduler.schedule(Topics.REQUESTED, source, Map.of(), 1, null);

        verify(repository).insertDurableRetry(eq("request-raw"), anyString(), eq("request-raw"),
                payload.capture(), anyString(), any(Instant.class), anyString());
        assertArrayEquals(bytes, payload.getValue());
    }

    @Test
    void preservesHeadersAndAddsRetryMetadata() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put(Headers.TRACEPARENT, "00-trace");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);

        scheduler.schedule(Topics.REQUESTED, record(Topics.REQUESTED, 0, 1),
                original, 2, new RuntimeException("timeout"));

        verify(json).toJson(headers.capture());
        assertEquals("00-trace", headers.getValue().get(Headers.TRACEPARENT));
        assertEquals(Topics.REQUESTED, headers.getValue().get(Headers.ORIGIN_TOPIC));
        assertEquals("2", headers.getValue().get(Headers.RETRY_ATTEMPT));
        assertEquals(String.valueOf(NOW.plusSeconds(4).toEpochMilli()),
                headers.getValue().get(Headers.RETRY_NOT_BEFORE));
        assertEquals("timeout", headers.getValue().get("x-retry-reason"));
    }

    @Test
    void deduplicationIdentityIncludesSourceCoordinatesAndAttempt() {
        assertEquals("retry:source-topic:5:99:3",
                DurableRetryScheduler.deduplicationKey(
                        new ConsumerRecord<>("source-topic", 5, 99L, "k", new byte[]{1}), 3));
    }

    @Test
    void reportsAlreadyScheduledRedeliveryWithoutCreatingAnotherIdentity() {
        when(repository.insertDurableRetry(anyString(), anyString(), anyString(),
                any(byte[].class), anyString(), any(Instant.class), anyString())).thenReturn(0);

        var result = scheduler.schedule(Topics.REQUESTED, record(Topics.REQUESTED, 2, 20),
                Map.of(), 1, new RuntimeException("again"));

        assertFalse(result.inserted());
        assertEquals("retry:payment.simulation.requested:2:20:1", result.deduplicationKey());
    }

    @Test
    void propagatesPersistenceFailureSoConsumerOffsetCannotCommit() {
        when(repository.insertDurableRetry(anyString(), anyString(), anyString(),
                any(byte[].class), anyString(), any(Instant.class), anyString()))
                .thenThrow(new IllegalStateException("postgres unavailable"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                scheduler.schedule(Topics.REQUESTED, record(Topics.REQUESTED, 0, 30),
                        Map.of(), 1, new RuntimeException("processing")));

        assertEquals("postgres unavailable", failure.getMessage());
    }

    @Test
    void rejectsUnknownOriginWithoutWritingOutbox() {
        assertThrows(IllegalArgumentException.class, () ->
                scheduler.schedule("unknown", record("unknown", 0, 1),
                        Map.of(), 1, new RuntimeException("failure")));

        verify(repository, never()).insertDurableRetry(anyString(), anyString(), anyString(),
                any(byte[].class), anyString(), any(Instant.class), anyString());
    }

    @Test
    void capsDueTimeAtConfiguredMaximumDelay() {
        var result = scheduler.schedule(Topics.REQUESTED, record(Topics.REQUESTED, 0, 44),
                Map.of(), 20, new RuntimeException("failure"));

        assertEquals(NOW.plusSeconds(10), result.dueAt());
    }

    private static ConsumerRecord<String, byte[]> record(String topic, int partition, long offset) {
        return new ConsumerRecord<>(topic, partition, offset, "request-1", new byte[]{1, 2, 3});
    }
}
