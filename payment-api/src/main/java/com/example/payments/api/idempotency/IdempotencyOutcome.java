package com.example.payments.api.idempotency;

/** Result of reserving an idempotency key against a request fingerprint (PAY-01/PAY-02/PAY-03). */
public sealed interface IdempotencyOutcome {

    /** First use of this key: the caller now owns it and may proceed. */
    record Reserved() implements IdempotencyOutcome {
    }

    /** Same key, same fingerprint, publish already confirmed: replay the original identity/result. */
    record Replay(String requestId) implements IdempotencyOutcome {
    }

    /**
     * Same key, same fingerprint, but the owning attempt never confirmed its publish and no
     * longer holds the lease. The caller resumes <em>that</em> requestId instead of waiting out
     * an orphaned reservation (PAY-03).
     */
    record ResumePublish(String requestId) implements IdempotencyOutcome {
    }

    /** Same key, different fingerprint: deterministic conflict, zero new publish. */
    record Conflict(String requestId) implements IdempotencyOutcome {
    }
}
