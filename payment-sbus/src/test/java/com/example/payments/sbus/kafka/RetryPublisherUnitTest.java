package com.example.payments.sbus.kafka;

import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.retry.DurableDeadLetterScheduler;
import com.example.payments.sbus.retry.DurableRetryScheduler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
