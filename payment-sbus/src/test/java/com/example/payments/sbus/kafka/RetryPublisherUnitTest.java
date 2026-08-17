package com.example.payments.sbus.kafka;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.retry.DurableDeadLetterScheduler;
import com.example.payments.sbus.retry.DurableRetryScheduler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RetryPublisherUnitTest {

    private DurableRetryScheduler scheduler;
    private DurableDeadLetterScheduler deadLetters;
    private SbusMetrics metrics;
    private RetryPublisher retryPublisher;
    private ConsumerRecord<String, byte[]> record;

    @BeforeEach
    void setUp() {
        scheduler = mock(DurableRetryScheduler.class);
        deadLetters = mock(DurableDeadLetterScheduler.class);
        metrics = mock(SbusMetrics.class);
        RetryProperties props = new RetryProperties();
        props.setMaxAttempts(3);
        retryPublisher = new RetryPublisher(scheduler, deadLetters, props, metrics);
        record = new ConsumerRecord<>(Topics.REQUESTED, 2, 10L, "k", new byte[]{1});
    }

    @Test
    void firstRetryIsDurablyScheduledWithAttemptOne() {
        HashMap<String, String> headers = new HashMap<>();
        RuntimeException failure = new RuntimeException("boom");

        retryPublisher.scheduleFirstRetry(Topics.REQUESTED, record, headers, failure);

        verify(scheduler).schedule(Topics.REQUESTED, record, headers, 1, failure);
        verify(deadLetters, never()).schedule(any(), any(), any(), any(), any());
    }

    @Test
    void schedulesNextAttemptWhenUnderLimit() {
        boolean dlq = retryPublisher.scheduleNextOrDlq(Topics.REQUESTED, record,
                new HashMap<>(), 1, new RuntimeException("boom"));

        assertFalse(dlq);
        verify(scheduler).schedule(eq(Topics.REQUESTED), eq(record), any(), eq(2), any());
        verify(deadLetters, never()).schedule(any(), any(), any(), any(), any());
    }

    @Test
    void routesToDlqWhenAttemptsExhausted() {
        boolean dlq = retryPublisher.scheduleNextOrDlq(Topics.REQUESTED, record,
                new HashMap<>(), 3, new RuntimeException("boom"));

        assertTrue(dlq);
        verify(deadLetters).schedule(eq(Topics.REQUESTED), eq(record), any(), any(),
                eq("retries-exhausted"));
    }

    // task_fc6d987b, Gap 1: when the record's own handling already failed AND persisting that
    // failure also fails (Postgres itself unreachable — the common case, since the same
    // dependency the handler needed is what the retry/DLQ scheduler needs too), the record must
    // not vanish without a trace. These prove the persistence failure is surfaced (metric +
    // rethrow, so the consumer's @ErrorStrategy keeps retrying instead of acknowledging) rather
    // than swallowed.

    @Test
    void firstRetryPersistenceFailureIsRecordedAndRethrownNotSwallowed() {
        RuntimeException handlerFailure = new RuntimeException("original handler failure");
        RuntimeException persistenceFailure = new RuntimeException("Postgres unreachable");
        doThrow(persistenceFailure).when(scheduler).schedule(any(), any(), any(), anyInt(), any());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryPublisher.scheduleFirstRetry(Topics.REQUESTED, record, new HashMap<>(), handlerFailure),
                "a persistence failure must propagate so the consumer's ErrorStrategy keeps retrying "
                        + "the record instead of silently acknowledging it");

        assertSame(persistenceFailure, thrown);
        verify(metrics).recordUnrecoverable();
    }

    @Test
    void dlqPersistenceFailureIsRecordedAndRethrownNotSwallowed() {
        RuntimeException handlerFailure = new RuntimeException("poison");
        RuntimeException persistenceFailure = new RuntimeException("Postgres unreachable");
        doThrow(persistenceFailure).when(deadLetters).schedule(any(), any(), any(), any(), any());

        assertThrows(RuntimeException.class,
                () -> retryPublisher.routeToDlq(Topics.REQUESTED, record, new HashMap<>(), handlerFailure, "poison"));

        verify(metrics).recordUnrecoverable();
    }

    @Test
    void aSuccessfulPersistNeverRecordsAnUnrecoverableMessage() {
        retryPublisher.scheduleFirstRetry(Topics.REQUESTED, record, new HashMap<>(), new RuntimeException("boom"));

        verify(metrics, never()).recordUnrecoverable();
    }

    // SEC-02: the SBUS_MESSAGE_AT_RISK log must carry only a recoverable pointer
    // (topic/partition/offset/key), never the payload in clear text or base64.

    @Test
    void logsOnlyThePointerNeverThePayloadOrItsBase64WhenPersistenceFails() {
        byte[] payload = "super-secret-payment-payload-content".getBytes();
        String payloadBase64 = Base64.getEncoder().encodeToString(payload);
        ConsumerRecord<String, byte[]> withPayload =
                new ConsumerRecord<>(Topics.REQUESTED, 2, 10L, "risk-key", payload);
        doThrow(new RuntimeException("Postgres unreachable"))
                .when(scheduler).schedule(any(), any(), any(), anyInt(), any());

        ListAppender<ILoggingEvent> appender = attachLogCapture();
        try {
            assertThrows(RuntimeException.class, () -> retryPublisher.scheduleFirstRetry(
                    Topics.REQUESTED, withPayload, new HashMap<>(), new RuntimeException("boom")));

            String logged = formattedMessages(appender);
            assertFalse(logged.contains(payloadBase64),
                    "log must never contain the record's payload base64-encoded");
            assertFalse(logged.toLowerCase().contains("payloadbase64"),
                    "log must not carry a payload field at all, named or otherwise");
            assertTrue(logged.contains(Topics.REQUESTED), "log must still carry the origin topic pointer");
            assertTrue(logged.contains("risk-key"), "log must still carry the record key pointer");
            assertTrue(logged.contains("10"), "log must still carry the record offset pointer");
        } finally {
            detachLogCapture(appender);
        }
    }

    // SEC-03: x-retry-reason/x-dlq-reason must be sanitized (truncated, payload-like content
    // redacted) before they are persisted as a Kafka header or column.

    @Test
    void sanitizeReasonTruncatesAnOverlyLongReason() {
        // Space-separated words (no single run >= 40 chars) so only truncation is exercised here,
        // not the payload-like redaction covered by its own test below.
        String longReason = "connection reset by peer ".repeat(30);

        String sanitized = RetryPublisher.sanitizeReason(longReason);

        assertEquals(500, sanitized.length(), "a reason over the 500-char limit must be truncated to it");
    }

    @Test
    void sanitizeReasonRedactsAnEmbeddedPayloadLikeRun() {
        String payloadLike = Base64.getEncoder().encodeToString("a fairly long embedded payload snippet".getBytes());
        String reason = "decode failed near " + payloadLike;

        String sanitized = RetryPublisher.sanitizeReason(reason);

        assertFalse(sanitized.contains(payloadLike), "a long payload-like run must be redacted, not persisted verbatim");
        assertTrue(sanitized.contains("[redacted]"), "the redaction marker must replace the payload-like run");
    }

    @Test
    void sanitizeReasonLeavesAnOrdinaryShortReasonUntouched() {
        assertEquals("timeout", RetryPublisher.sanitizeReason("timeout"));
    }

    @Test
    void sanitizeReasonPassesNullThrough() {
        assertNull(RetryPublisher.sanitizeReason(null));
    }

    @Test
    void sanitizeHeadersRedactsPreExistingRiskHeadersBeforeForwarding() {
        String payloadLike = Base64.getEncoder().encodeToString("another sizeable embedded payload chunk".getBytes());
        Map<String, String> headers = new HashMap<>();
        headers.put("x-retry-reason", "earlier hop failure: " + payloadLike);
        headers.put("x-dlq-reason", "earlier hop failure: " + payloadLike);
        headers.put("x-correlation-id", "keep-me-untouched");

        Map<String, String> sanitized = RetryPublisher.sanitizeHeaders(headers);

        assertFalse(sanitized.get("x-retry-reason").contains(payloadLike));
        assertFalse(sanitized.get("x-dlq-reason").contains(payloadLike));
        assertEquals("keep-me-untouched", sanitized.get("x-correlation-id"),
                "an unrelated header must pass through unchanged");
    }

    private static ListAppender<ILoggingEvent> attachLogCapture() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(RetryPublisher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogCapture(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(RetryPublisher.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static String formattedMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }
}
