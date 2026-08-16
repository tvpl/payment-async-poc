package com.example.payments.api.controller;

import java.util.regex.Pattern;

/**
 * Validates the {@code Idempotency-Key} header before any domain I/O (IDEM-01/IDEM-02): absent,
 * blank, over 128 characters, or outside {@code [A-Za-z0-9_-]+} is rejected. Shared by every
 * controller on the public contract so the rule is identical on {@code /payment-simulations} and
 * {@code /v0/payment-simulations}.
 */
public final class IdempotencyKeyValidation {

    private static final int MAX_LENGTH = 128;
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]+");

    private IdempotencyKeyValidation() {
    }

    public static boolean isValid(String idempotencyKey) {
        return idempotencyKey != null
                && !idempotencyKey.isBlank()
                && idempotencyKey.length() <= MAX_LENGTH
                && ALLOWED.matcher(idempotencyKey).matches();
    }
}
