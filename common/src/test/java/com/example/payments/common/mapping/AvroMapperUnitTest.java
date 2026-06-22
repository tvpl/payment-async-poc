package com.example.payments.common.mapping;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.model.Fees;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AvroMapperUnitTest {

    @Test
    void requestedRoundTripPreservesEnvelopeAndPayload() {
        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");
        var env = EventEnvelope.create(EventTypes.PAYMENT_SIMULATION_REQUESTED,
                "req-1", "corr-1", "req-1", "trace-1", Sources.API, payload);

        var back = AvroMapper.fromAvro(AvroMapper.toAvroRequested(env));

        assertEquals(env.eventId(), back.eventId());
        assertEquals(env.requestId(), back.requestId());
        assertEquals(env.correlationId(), back.correlationId());
        assertEquals(env.causationId(), back.causationId());
        assertEquals(env.occurredAt().toEpochMilli(), back.occurredAt().toEpochMilli());
        assertEquals(new BigDecimal("125.50"), back.payload().amount());
        assertEquals("VISA", back.payload().brand());
        assertEquals(3, back.payload().installments());
    }

    @Test
    void completedRoundTripPreservesResult() {
        var result = new SimulationResult("sim-1", "req-1", SimulationResult.APPROVED, "123456",
                new BigDecimal("125.50"), "BRL", 3,
                new Fees(new BigDecimal("2.49"), new BigDecimal("1.25"), new BigDecimal("122.38")),
                new Settlement(LocalDate.parse("2026-06-22"), "D+1"), null, null);
        var env = EventEnvelope.create(EventTypes.PAYMENT_SIMULATION_COMPLETED,
                "req-1", "corr-1", "cause-1", "trace-1", Sources.SBUS, result);

        var back = AvroMapper.fromAvro(AvroMapper.toAvroCompleted(env));

        assertEquals("123456", back.payload().authorizationCode());
        assertEquals(new BigDecimal("122.38"), back.payload().fees().netAmount());
        assertEquals(LocalDate.parse("2026-06-22"), back.payload().settlement().settlementDate());
        assertNull(back.payload().errorCode());
    }
}
