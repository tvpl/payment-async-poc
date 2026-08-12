package com.example.platform.asyncredis.api;

/** Persisted lifecycle state of an accepted job (RED-01). */
public enum JobState {

    /** Accepted and queryable; the result has not been released yet. */
    PROCESSING,

    /** The worker released a result. Terminal. */
    COMPLETED,

    /**
     * The idempotency reservation was taken but the stream {@code XADD} failed — this jobId was
     * never really accepted, no worker will ever see it. Not terminal: {@link
     * com.example.platform.asyncredis.api.JobAcceptanceService} retries the enqueue against the
     * same reservation on the next request carrying the same Idempotency-Key, instead of a
     * {@code Replay} pointing at a job that doesn't exist on any stream.
     */
    ENQUEUE_FAILED
}
