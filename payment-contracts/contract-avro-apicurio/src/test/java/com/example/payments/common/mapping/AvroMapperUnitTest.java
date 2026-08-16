package com.example.payments.common.mapping;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.common.model.Fees;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.ProcessPaymentSimulationCommandPayload;
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
        var payload = request();
        var envelope = envelope(EventTypes.PAYMENT_SIMULATION_REQUESTED, Sources.API, payload);

        var actual = AvroMapper.fromAvro(AvroMapper.toAvroRequested(envelope));

        assertEnvelope(envelope, actual);
        assertRequest(payload, actual.payload());
    }

    @Test
    void commandRoundTripPreservesEnvelopeAndPayload() {
        var payload = new ProcessPaymentSimulationCommandPayload("simulation-1", request());
        var envelope = envelope(EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND, Sources.SBUS, payload);

        var actual = AvroMapper.fromAvro(AvroMapper.toAvroCommand(envelope));

        assertEnvelope(envelope, actual);
        assertEquals("simulation-1", actual.payload().simulationId());
        assertRequest(payload.request(), actual.payload().request());
    }

    @Test
    void coreResponseRoundTripPreservesEnvelopeAndPayload() {
        var payload = new CorePaymentSimulationResponsePayload(
                "simulation-1", "APPROVED", "123456", new BigDecimal("125.50"), "BRL", 3,
                fees(), settlement(), null, null);
        var envelope = envelope(EventTypes.CORE_PAYMENT_SIMULATION_RESPONSE, Sources.CORE, payload);

        var actual = AvroMapper.fromAvro(AvroMapper.toAvroCoreResponse(envelope));

        assertEnvelope(envelope, actual);
        assertCoreResponse(payload, actual.payload());
    }

    @Test
    void completedRoundTripPreservesEnvelopeAndPayload() {
        var payload = result(SimulationResult.APPROVED, "123456", null, null);
        var envelope = envelope(EventTypes.PAYMENT_SIMULATION_COMPLETED, Sources.SBUS, payload);

        var actual = AvroMapper.fromAvro(AvroMapper.toAvroCompleted(envelope));

        assertEnvelope(envelope, actual);
        assertResult(payload, actual.payload());
    }

    @Test
    void failedRoundTripPreservesEnvelopeAndPayload() {
        var payload = result(SimulationResult.ERROR, null, "CORE_TIMEOUT", "Core timed out");
        var envelope = envelope(EventTypes.PAYMENT_SIMULATION_FAILED, Sources.SBUS, payload);

        var actual = AvroMapper.fromAvro(AvroMapper.toAvroFailed(envelope));

        assertEnvelope(envelope, actual);
        assertResult(payload, actual.payload());
    }

    private static PaymentSimulationRequestPayload request() {
        return new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3,
                "AUTHORIZE_AND_CAPTURE");
    }

    private static Fees fees() {
        return new Fees(new BigDecimal("2.49"), new BigDecimal("1.25"), new BigDecimal("122.38"));
    }

    private static Settlement settlement() {
        return new Settlement(LocalDate.parse("2026-06-22"), "D+1");
    }

    private static SimulationResult result(String status, String authorizationCode, String errorCode, String errorMessage) {
        return new SimulationResult(
                "simulation-1", "request-1", status, authorizationCode, new BigDecimal("125.50"), "BRL", 3,
                fees(), settlement(), errorCode, errorMessage);
    }

    private static <T> EventEnvelope<T> envelope(String eventType, String source, T payload) {
        return EventEnvelope.create(
                eventType, "request-1", "correlation-1", "cause-1", "trace-1", source, "tenant-42", payload);
    }

    private static void assertEnvelope(EventEnvelope<?> expected, EventEnvelope<?> actual) {
        assertEquals(expected.eventId(), actual.eventId());
        assertEquals(expected.eventType(), actual.eventType());
        assertEquals(expected.eventVersion(), actual.eventVersion());
        assertEquals(expected.occurredAt().toEpochMilli(), actual.occurredAt().toEpochMilli());
        assertEquals(expected.requestId(), actual.requestId());
        assertEquals(expected.correlationId(), actual.correlationId());
        assertEquals(expected.causationId(), actual.causationId());
        assertEquals(expected.traceId(), actual.traceId());
        assertEquals(expected.source(), actual.source());
        assertEquals(expected.tenantId(), actual.tenantId());
    }

    private static void assertRequest(
            PaymentSimulationRequestPayload expected,
            PaymentSimulationRequestPayload actual) {
        assertEquals(expected.merchantId(), actual.merchantId());
        assertEquals(expected.amount(), actual.amount());
        assertEquals(expected.currency(), actual.currency());
        assertEquals(expected.paymentMethod(), actual.paymentMethod());
        assertEquals(expected.brand(), actual.brand());
        assertEquals(expected.installments(), actual.installments());
        assertEquals(expected.captureMode(), actual.captureMode());
    }

    private static void assertCoreResponse(
            CorePaymentSimulationResponsePayload expected,
            CorePaymentSimulationResponsePayload actual) {
        assertEquals(expected.simulationId(), actual.simulationId());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.authorizationCode(), actual.authorizationCode());
        assertEquals(expected.amount(), actual.amount());
        assertEquals(expected.currency(), actual.currency());
        assertEquals(expected.installments(), actual.installments());
        assertFees(expected.fees(), actual.fees());
        assertSettlement(expected.settlement(), actual.settlement());
        assertNull(actual.errorCode());
        assertNull(actual.errorMessage());
    }

    private static void assertResult(SimulationResult expected, SimulationResult actual) {
        assertEquals(expected.simulationId(), actual.simulationId());
        assertEquals(expected.requestId(), actual.requestId());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.authorizationCode(), actual.authorizationCode());
        assertEquals(expected.amount(), actual.amount());
        assertEquals(expected.currency(), actual.currency());
        assertEquals(expected.installments(), actual.installments());
        assertFees(expected.fees(), actual.fees());
        assertSettlement(expected.settlement(), actual.settlement());
        assertEquals(expected.errorCode(), actual.errorCode());
        assertEquals(expected.errorMessage(), actual.errorMessage());
    }

    private static void assertFees(Fees expected, Fees actual) {
        assertEquals(expected.mdr(), actual.mdr());
        assertEquals(expected.interchange(), actual.interchange());
        assertEquals(expected.netAmount(), actual.netAmount());
    }

    private static void assertSettlement(Settlement expected, Settlement actual) {
        assertEquals(expected.settlementDate(), actual.settlementDate());
        assertEquals(expected.settlementType(), actual.settlementType());
    }
}
