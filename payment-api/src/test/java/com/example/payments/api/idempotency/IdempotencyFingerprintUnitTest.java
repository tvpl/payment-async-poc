package com.example.payments.api.idempotency;

import com.example.payments.api.dto.PaymentSimulationRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IdempotencyFingerprintUnitTest {

    private static PaymentSimulationRequest request(BigDecimal amount) {
        return new PaymentSimulationRequest(
                "MERCHANT-001", amount, "BRL", "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");
    }

    @Test
    void isDeterministicForTheSamePayload() {
        var a = request(new BigDecimal("125.50"));
        var b = request(new BigDecimal("125.50"));

        assertEquals(IdempotencyFingerprint.of(a), IdempotencyFingerprint.of(b));
    }

    @Test
    void treatsEquivalentAmountScaleAsTheSameFingerprint() {
        var a = request(new BigDecimal("125.50"));
        var b = request(new BigDecimal("125.5"));

        assertEquals(IdempotencyFingerprint.of(a), IdempotencyFingerprint.of(b));
    }

    @Test
    void differsWhenAmountValueDiffers() {
        var a = request(new BigDecimal("125.50"));
        var b = request(new BigDecimal("125.51"));

        assertNotEquals(IdempotencyFingerprint.of(a), IdempotencyFingerprint.of(b));
    }

    @Test
    void differsWhenMerchantIdDiffers() {
        var a = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
        var b = new PaymentSimulationRequest(
                "MERCHANT-002", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");

        assertNotEquals(IdempotencyFingerprint.of(a), IdempotencyFingerprint.of(b));
    }

    @Test
    void differsWhenInstallmentsDiffer() {
        var a = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
        var b = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 2, "AUTHORIZE_AND_CAPTURE");

        assertNotEquals(IdempotencyFingerprint.of(a), IdempotencyFingerprint.of(b));
    }

    @Test
    void doesNotConfuseFieldBoundariesAcrossConcatenation() {
        // "AB" + "1" must not fingerprint the same as "A" + "B1" — the delimiter prevents this.
        var a = new PaymentSimulationRequest(
                "AB", new BigDecimal("1.00"), "BRL", "1", null, 1, "AUTHORIZE_AND_CAPTURE");
        var b = new PaymentSimulationRequest(
                "A", new BigDecimal("1.00"), "BRL", "B1", null, 1, "AUTHORIZE_AND_CAPTURE");

        assertNotEquals(IdempotencyFingerprint.of(a), IdempotencyFingerprint.of(b));
    }

    /**
     * task_T8 (AUD-18): before escaping, a literal {@code |} inside a free-text field forged a
     * field boundary — {@code paymentMethod="pm|X", brand="1"} and
     * {@code paymentMethod="pm", brand="X|1"} joined ("...|pm|X|1|...") to the identical
     * canonical string, so two genuinely different payloads produced the same fingerprint
     * (delimiter-injection collision).
     */
    @Test
    void doesNotCollideWhenAFieldContainsTheDelimiterItself() {
        var a = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "pm|X", "1", 1, "AUTHORIZE_AND_CAPTURE");
        var b = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "pm", "X|1", 1, "AUTHORIZE_AND_CAPTURE");

        assertNotEquals(IdempotencyFingerprint.of(a), IdempotencyFingerprint.of(b));
    }

    /**
     * Escaping the delimiter without also escaping a pre-existing backslash first is its own
     * collision class: with only {@code |} escaped, {@code paymentMethod="\", brand="|a"} and
     * {@code paymentMethod="|\\", brand="a"} both join to the identical {@code "\|\|a"} - the
     * backslash from one field's raw data is mistaken for the escape marker of the delimiter
     * that follows it. Escaping {@code \} first (before {@code |}) is what prevents this.
     */
    @Test
    void doesNotCollideWhenAFieldsOwnBackslashLooksLikeAnEscapedDelimiter() {
        var a = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "\\", "|a", 1, "AUTHORIZE_AND_CAPTURE");
        var b = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "|\\", "a", 1, "AUTHORIZE_AND_CAPTURE");

        assertNotEquals(IdempotencyFingerprint.of(a), IdempotencyFingerprint.of(b));
    }
}
