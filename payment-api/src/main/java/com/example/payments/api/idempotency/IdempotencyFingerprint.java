package com.example.payments.api.idempotency;

import com.example.payments.api.dto.PaymentSimulationRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic fingerprint of the business fields that make two submissions "the same
 * request" (PAY-01/PAY-02). Numeric amount is normalized ({@link java.math.BigDecimal#stripTrailingZeros()})
 * so {@code 125.50} and {@code 125.5} fingerprint identically - scale is not part of the
 * business identity of the request, matching the equality precedent used for the same
 * value elsewhere in the payment contract. Fields are pipe-delimited before hashing so
 * adjacent values cannot be confused for one another at a boundary.
 */
public final class IdempotencyFingerprint {

    private static final String DELIMITER = "|";

    private IdempotencyFingerprint() {
    }

    public static String of(PaymentSimulationRequest request) {
        String canonical = String.join(DELIMITER,
                nullToEmpty(request.merchantId()),
                request.amount().stripTrailingZeros().toPlainString(),
                nullToEmpty(request.currency()),
                nullToEmpty(request.paymentMethod()),
                nullToEmpty(request.brand()),
                String.valueOf(request.installments()),
                nullToEmpty(request.captureMode()));
        return sha256Hex(canonical);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
