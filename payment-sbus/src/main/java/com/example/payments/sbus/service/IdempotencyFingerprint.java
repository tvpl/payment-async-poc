package com.example.payments.sbus.service;

import com.example.payments.common.model.PaymentSimulationRequestPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic fingerprint of the business fields that make two requests "the same request"
 * (AUD-01). Ported from payment-api's {@code IdempotencyFingerprint} (the API's fixed,
 * delimiter-escaped version — see its javadoc) so the SBUS computes this independently from the
 * payload it actually received, never trusting an API-asserted header for a money decision
 * (design §5). Numeric amount is normalized ({@link java.math.BigDecimal#stripTrailingZeros()})
 * so {@code 125.50} and {@code 125.5} fingerprint identically. Fields are pipe-delimited before
 * hashing, each escaped first ({@code \} then {@code |}) so a literal delimiter inside a
 * free-text field cannot forge a boundary and collide with a different payload.
 */
public final class IdempotencyFingerprint {

    private static final String DELIMITER = "|";

    private IdempotencyFingerprint() {
    }

    public static String of(PaymentSimulationRequestPayload payload) {
        String canonical = String.join(DELIMITER,
                escape(nullToEmpty(payload.merchantId())),
                escape(payload.amount().stripTrailingZeros().toPlainString()),
                escape(nullToEmpty(payload.currency())),
                escape(nullToEmpty(payload.paymentMethod())),
                escape(nullToEmpty(payload.brand())),
                String.valueOf(payload.installments()),
                escape(nullToEmpty(payload.captureMode())));
        return sha256Hex(canonical);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace(DELIMITER, "\\" + DELIMITER);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to fingerprint idempotent requests", e);
        }
    }
}
