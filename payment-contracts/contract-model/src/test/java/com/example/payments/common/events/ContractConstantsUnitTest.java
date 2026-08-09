package com.example.payments.common.events;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContractConstantsUnitTest {

    @Test
    void exposesEveryCanonicalEventType() {
        assertEquals(Set.of(
                "PaymentSimulationRequested",
                "ProcessPaymentSimulationCommand",
                "CorePaymentSimulationResponse",
                "PaymentSimulationCompleted",
                "PaymentSimulationFailed"
        ), Set.of(
                EventTypes.PAYMENT_SIMULATION_REQUESTED,
                EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND,
                EventTypes.CORE_PAYMENT_SIMULATION_RESPONSE,
                EventTypes.PAYMENT_SIMULATION_COMPLETED,
                EventTypes.PAYMENT_SIMULATION_FAILED
        ));
    }

    @Test
    void exposesEveryCanonicalHeader() {
        assertEquals(Set.of(
                "x-request-id",
                "x-correlation-id",
                "x-causation-id",
                "Idempotency-Key",
                "x-event-type",
                "x-event-version",
                "traceparent",
                "x-retry-attempt",
                "x-retry-not-before",
                "x-orig-topic"
        ), Set.of(
                Headers.REQUEST_ID,
                Headers.CORRELATION_ID,
                Headers.CAUSATION_ID,
                Headers.IDEMPOTENCY_KEY,
                Headers.EVENT_TYPE,
                Headers.EVENT_VERSION,
                Headers.TRACEPARENT,
                Headers.RETRY_ATTEMPT,
                Headers.RETRY_NOT_BEFORE,
                Headers.ORIGIN_TOPIC
        ));
    }

    @Test
    void preservesEveryKafkaTopic() {
        assertEquals(Set.of(
                "payment.simulation.requested",
                "payment.simulation.core.command",
                "payment.simulation.core.response",
                "payment.simulation.completed",
                "payment.simulation.failed",
                "payment.simulation.dlq",
                "payment.simulation.requested.retry",
                "payment.simulation.core.response.retry"
        ), Set.of(
                Topics.REQUESTED,
                Topics.CORE_COMMAND,
                Topics.CORE_RESPONSE,
                Topics.COMPLETED,
                Topics.FAILED,
                Topics.DLQ,
                Topics.REQUESTED_RETRY,
                Topics.CORE_RESPONSE_RETRY
        ));
    }

    @Test
    void preservesEveryEventSource() {
        assertEquals(
                Set.of("payment-simulation-api", "payment-sbus", "payment-core-mock"),
                Set.of(Sources.API, Sources.SBUS, Sources.CORE)
        );
    }
}
