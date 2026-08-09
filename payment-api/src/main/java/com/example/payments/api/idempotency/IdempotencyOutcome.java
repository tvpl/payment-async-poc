package com.example.payments.api.idempotency;

/** Result of reserving an idempotency key against a request fingerprint (PAY-01/PAY-02). */
public sealed interface IdempotencyOutcome {

    /** First use of this key: the caller now owns it and may proceed. */
    record Reserved() implements IdempotencyOutcome {
    }

    /** Same key, same fingerprint: replay the original request's identity/result. */
    record Replay(String requestId) implements IdempotencyOutcome {
    }

    /** Same key, different fingerprint: deterministic conflict, zero new publish. */
    record Conflict(String requestId) implements IdempotencyOutcome {
    }
}
