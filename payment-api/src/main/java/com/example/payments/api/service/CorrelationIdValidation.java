package com.example.payments.api.service;

import java.util.regex.Pattern;

/**
 * Validates an inbound {@code x-correlation-id} header (OBS-03). The accepted shape is a UUID
 * or the general {@code [A-Za-z0-9-]{8,64}} pattern - a standard UUID (36 hyphenated hex
 * characters) already satisfies that pattern, so a single regex covers both without a separate
 * UUID parse.
 *
 * <p>Unlike {@link com.example.payments.api.controller.IdempotencyKeyValidation}, an invalid
 * value here is never a reason to reject the request (OBS-03): the caller ({@link
 * ApiPaymentService}) ignores it and generates a fresh id instead of surfacing a 4xx.
 */
final class CorrelationIdValidation {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private CorrelationIdValidation() {
    }

    static boolean isValid(String correlationId) {
        return correlationId != null && ALLOWED.matcher(correlationId).matches();
    }
}
