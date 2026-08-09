package com.example.payments.common.events;

import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventEnvelopeJsonContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void preservesEnvelopeAndPayloadJsonFieldNamesAndValues() throws Exception {
        var envelope = new EventEnvelope<>(
                "event-1",
                EventTypes.PAYMENT_SIMULATION_REQUESTED,
                "1.0",
                Instant.parse("2026-08-08T12:00:00Z"),
                "request-1",
                "correlation-1",
                "cause-1",
                "trace-1",
                Sources.API,
                new PaymentSimulationRequestPayload(
                        "merchant-1",
                        new BigDecimal("125.50"),
                        "BRL",
                        "CREDIT_CARD",
                        "VISA",
                        3,
                        "AUTHORIZE_AND_CAPTURE"
                )
        );

        var json = objectMapper.valueToTree(envelope);
        var fieldNames = new HashSet<String>();
        json.fieldNames().forEachRemaining(fieldNames::add);

        assertEquals(Set.of(
                "eventId", "eventType", "eventVersion", "occurredAt", "requestId",
                "correlationId", "causationId", "traceId", "source", "payload"
        ), fieldNames);
        assertEquals("request-1", json.get("requestId").asText());
        var amount = json.get("payload").get("amount");
        assertTrue(amount.isNumber());
        assertEquals(0, new BigDecimal("125.50").compareTo(amount.decimalValue()));
        assertEquals("VISA", json.get("payload").get("brand").asText());
    }
}
