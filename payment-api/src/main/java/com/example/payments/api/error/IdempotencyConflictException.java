package com.example.payments.api.error;

/** Raised when an idempotency key is reused with a different payload fingerprint (PAY-02). */
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;
    private final String originalRequestId;

    public IdempotencyConflictException(String idempotencyKey, String originalRequestId) {
        super("Idempotency key '" + idempotencyKey + "' was already used with a different payload");
        this.idempotencyKey = idempotencyKey;
        this.originalRequestId = originalRequestId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String originalRequestId() {
        return originalRequestId;
    }
}
