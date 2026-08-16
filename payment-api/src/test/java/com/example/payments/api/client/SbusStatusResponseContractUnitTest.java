package com.example.payments.api.client;

import com.example.payments.common.model.Fees;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;
import com.fasterxml.jackson.databind.JsonNode;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * API-03: consumer-driven contract test for the internal HTTP contract Edge&harr;Sbus
 * ({@code GET /internal/payment-simulations/{id}}), mirroring {@code payment-sbus}'s
 * {@code SbusStatusViewContractUnitTest} (T14). Serializes the REAL {@link SbusStatusResponse}
 * (the type {@link SbusStatusClient} actually deserializes into) and compares it, field by
 * field, against the SAME versioned JSON fixture used on the SBUS side. The two fixture files
 * are byte-for-byte identical by construction — {@code scripts/e2e/check_internal_contract.py}
 * (T15) fails the workspace gate if they ever drift apart, which is what actually catches a
 * one-sided rename; this test only proves this module's own type still matches today's fixture.
 */
class SbusStatusResponseContractUnitTest {

    private static final String FIXTURE_PATH = "/contracts/internal-status.json";

    @Test
    void serializedSbusStatusResponseMatchesTheCanonicalFixture() throws IOException {
        SbusStatusResponse response = canonicalResponse();

        String actualJson = ObjectMapper.getDefault().writeValueAsString(response);

        com.fasterxml.jackson.databind.ObjectMapper treeMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode actual = treeMapper.readTree(actualJson);
        JsonNode expected = treeMapper.readTree(fixtureText());

        assertEquals(expected, actual,
                "SbusStatusResponse's serialized shape diverged from the canonical fixture at "
                        + FIXTURE_PATH + " — a field was renamed, added, or removed. If this is an "
                        + "intentional contract change, update the fixture in BOTH payment-api and "
                        + "payment-sbus (T14) together.");
    }

    private static SbusStatusResponse canonicalResponse() {
        SimulationResult result = new SimulationResult(
                "sim-canonical-001",
                "req-canonical-001",
                SimulationResult.APPROVED,
                "AUTH123",
                new BigDecimal("125.50"),
                "BRL",
                3,
                new Fees(new BigDecimal("2.50"), new BigDecimal("1.10"), new BigDecimal("121.90")),
                new Settlement(LocalDate.of(2026, 8, 20), "D+2"),
                null,
                null);
        return new SbusStatusResponse("req-canonical-001", "COMPLETED", result);
    }

    private static String fixtureText() throws IOException {
        try (InputStream in = SbusStatusResponseContractUnitTest.class.getResourceAsStream(FIXTURE_PATH)) {
            if (in == null) {
                fail("Fixture not found on classpath: " + FIXTURE_PATH);
            }
            return new String(in.readAllBytes());
        }
    }
}
