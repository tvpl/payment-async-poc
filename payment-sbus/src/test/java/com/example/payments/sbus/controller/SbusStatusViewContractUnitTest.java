package com.example.payments.sbus.controller;

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
 * ({@code GET /internal/payment-simulations/{id}}). Serializes the REAL {@link SbusStatusView}
 * (the type {@link InternalStatusController} actually returns) and compares it, field by field,
 * against a versioned JSON fixture shared with {@code payment-api}'s equivalent test for {@code
 * SbusStatusResponse} (see T15, {@code scripts/e2e/check_internal_contract.py}). Renaming,
 * adding, or removing a field on either side breaks this test — a silent divergence between the
 * two sides of the contract is exactly what API-03 exists to catch.
 */
class SbusStatusViewContractUnitTest {

    private static final String FIXTURE_PATH = "/contracts/internal-status.json";

    @Test
    void serializedSbusStatusViewMatchesTheCanonicalFixture() throws IOException {
        SbusStatusView view = canonicalView();

        String actualJson = ObjectMapper.getDefault().writeValueAsString(view);

        com.fasterxml.jackson.databind.ObjectMapper treeMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode actual = treeMapper.readTree(actualJson);
        JsonNode expected = treeMapper.readTree(fixtureText());

        assertEquals(expected, actual,
                "SbusStatusView's serialized shape diverged from the canonical fixture at "
                        + FIXTURE_PATH + " — a field was renamed, added, or removed. If this is an "
                        + "intentional contract change, update the fixture in BOTH payment-sbus and "
                        + "payment-api (T15) together.");
    }

    private static SbusStatusView canonicalView() {
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
        return new SbusStatusView("req-canonical-001", "COMPLETED", result);
    }

    private static String fixtureText() throws IOException {
        try (InputStream in = SbusStatusViewContractUnitTest.class.getResourceAsStream(FIXTURE_PATH)) {
            if (in == null) {
                fail("Fixture not found on classpath: " + FIXTURE_PATH);
            }
            return new String(in.readAllBytes());
        }
    }
}
