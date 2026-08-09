package com.example.payments.sbus.kafka;

import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.retry.DurableDeadLetterScheduler;
import com.example.payments.sbus.retry.DurableRetryScheduler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RetryPublisherUnitTest {

    private DurableRetryScheduler scheduler;
    private DurableDeadLetterScheduler deadLetters;
    private RetryPublisher retryPublisher;
    private ConsumerRecord<String, byte[]> record;

    @BeforeEach
    void setUp() {
        scheduler = mock(DurableRetryScheduler.class);
        deadLetters = mock(DurableDeadLetterScheduler.class);
        RetryProperties props = new RetryProperties();
        props.setMaxAttempts(3);
        retryPublisher = new RetryPublisher(scheduler, deadLetters, props);
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
}
