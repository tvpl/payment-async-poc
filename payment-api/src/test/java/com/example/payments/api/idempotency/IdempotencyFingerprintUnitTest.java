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
}
