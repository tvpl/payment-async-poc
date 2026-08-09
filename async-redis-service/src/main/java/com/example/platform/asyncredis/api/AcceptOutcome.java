package com.example.platform.asyncredis.api;

/** Result of reserving an idempotency key against a job fingerprint (RED-08). */
public sealed interface AcceptOutcome {

    /** First use of this key: the caller owns it and the job was enqueued. */
    record Accepted(String jobId) implements AcceptOutcome {
    }

    /** Same key, same payload: the original job is returned and nothing new is enqueued. */
    record Replay(String jobId) implements AcceptOutcome {
    }

    /** Same key, different payload: deterministic conflict, nothing enqueued. */
    record Conflict(String jobId) implements AcceptOutcome {
    }
}
