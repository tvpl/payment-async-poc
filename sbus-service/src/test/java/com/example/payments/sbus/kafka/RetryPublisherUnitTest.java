package com.example.payments.sbus.kafka;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.metrics.SbusMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RetryPublisherUnitTest {

    private KafkaPublisher publisher;
    private RetryPublisher retryPublisher;

    @BeforeEach
    void setUp() {
        publisher = mock(KafkaPublisher.class);
        RetryProperties props = new RetryProperties();
        props.setMaxAttempts(3);
        retryPublisher = new RetryPublisher(publisher, props, mock(SbusMetrics.class));
    }

    @Test
    void firstRetryGoesToRetryTopicWithAttemptOne() {
        retryPublisher.scheduleFirstRetry(Topics.REQUESTED, "k", new byte[]{1},
                new HashMap<>(), new RuntimeException("boom"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(publisher).send(eq(Topics.REQUESTED_RETRY), eq("k"), any(), headers.capture());
        assertEquals("1", headers.getValue().get(Headers.RETRY_ATTEMPT));
        assertEquals(Topics.REQUESTED, headers.getValue().get(Headers.ORIGIN_TOPIC));
    }

    @Test
    void schedulesNextAttemptWhenUnderLimit() {
        boolean dlq = retryPublisher.scheduleNextOrDlq(Topics.REQUESTED, "k", new byte[]{1},
                new HashMap<>(), 1, new RuntimeException("boom"));

        assertFalse(dlq);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(publisher).send(eq(Topics.REQUESTED_RETRY), eq("k"), any(), headers.capture());
        assertEquals("2", headers.getValue().get(Headers.RETRY_ATTEMPT));
    }

    @Test
    void routesToDlqWhenAttemptsExhausted() {
        boolean dlq = retryPublisher.scheduleNextOrDlq(Topics.REQUESTED, "k", new byte[]{1},
                new HashMap<>(), 3, new RuntimeException("boom"));

        assertTrue(dlq);
        verify(publisher).send(eq(Topics.DLQ), eq("k"), any(), any());
    }
}
