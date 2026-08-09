package com.example.payments.common.events;

import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.ProcessPaymentSimulationCommandPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EventEnvelopeUnitTest {

    @Test
    void createStampsIdentityAndVersion() {
        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");
        var env = EventEnvelope.create(
                EventTypes.PAYMENT_SIMULATION_REQUESTED,
                "req-1", "corr-1", "req-1", "trace-1", Sources.API, payload);

        assertNotNull(env.eventId());
        assertEquals(EventEnvelope.CURRENT_VERSION, env.eventVersion());
        assertEquals("req-1", env.requestId());
        assertEquals("corr-1", env.correlationId());
        assertNotNull(env.occurredAt());
    }

    @Test
    void deriveKeepsCorrelationAndChainsCausation() {
        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE");
        var requested = EventEnvelope.create(
                EventTypes.PAYMENT_SIMULATION_REQUESTED,
                "req-1", "corr-1", "req-1", "trace-1", Sources.API, payload);

        var command = requested.deriveAs(
                EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND,
                Sources.SBUS,
                new ProcessPaymentSimulationCommandPayload("sim-1", payload));

        // Correlation identity is preserved end to end...
        assertEquals("req-1", command.requestId());
        assertEquals("corr-1", command.correlationId());
        assertEquals("trace-1", command.traceId());
        // ...while causation points at the event that produced this one.
        assertEquals(requested.eventId(), command.causationId());
        assertNotEquals(requested.eventId(), command.eventId());
        assertEquals(EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND, command.eventType());
    }
}
