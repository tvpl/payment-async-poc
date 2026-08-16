package com.example.payments.sbus.support;

import com.example.payments.common.events.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * task_89c681c8: this utility was correct but never called anywhere in the codebase — every
 * SBUS log line went out without requestId/correlationId/causationId/traceId as real JSON keys.
 * Wired into SimulationMessageHandler now; this proves the utility's own contract.
 */
class MdcUnitTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void fromConsumerPopulatesEveryCorrelationFieldFromTheEnvelope() {
        EventEnvelope<String> env = new EventEnvelope<>(
                "event-1", "PaymentSimulationRequested", EventEnvelope.CURRENT_VERSION, Instant.now(),
                "req-1", "corr-1", "cause-1", "trace-1", "sbus-test", "", "payload");
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>("payment.simulation.requested", 2, 42L, "key", new byte[0]);

        Mdc.fromConsumer(record, env);

        assertEquals("req-1", MDC.get("requestId"));
        assertEquals("corr-1", MDC.get("correlationId"));
        assertEquals("cause-1", MDC.get("causationId"));
        assertEquals("trace-1", MDC.get("traceId"));
        assertEquals("PaymentSimulationRequested", MDC.get("eventType"));
        assertEquals("payment.simulation.requested", MDC.get("topic"));
        assertEquals("2", MDC.get("partition"));
        assertEquals("42", MDC.get("offset"));
    }

    @Test
    void clearRemovesEveryFieldSoTheNextLogLineOnTheThreadDoesNotLeakCorrelationIds() {
        EventEnvelope<String> env = new EventEnvelope<>(
                "event-1", "PaymentSimulationRequested", EventEnvelope.CURRENT_VERSION, Instant.now(),
                "req-1", "corr-1", "cause-1", "trace-1", "sbus-test", "", "payload");
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>("payment.simulation.requested", 0, 0L, "key", new byte[0]);
        Mdc.fromConsumer(record, env);

        Mdc.clear();

        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("correlationId"));
        assertNull(MDC.get("causationId"));
        assertNull(MDC.get("traceId"));
    }
}
