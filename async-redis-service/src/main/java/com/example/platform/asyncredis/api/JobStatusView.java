package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.dto.JobResult;

/**
 * What a poll can observe about a job (RED-01). The five cases are deliberately distinct: a job that
 * was never accepted is not the same as one still running, and a finished job whose result payload
 * has aged out is not the same as one that never existed.
 */
public sealed interface JobStatusView {

    /** No such job, or its status has aged out entirely. */
    record Unknown() implements JobStatusView {
    }

    /** Accepted and still in flight. */
    record Processing() implements JobStatusView {
    }

    /** Terminal, with the result still retained. */
    record Completed(JobResult result) implements JobStatusView {
    }

    /** Terminal, but the result payload outlived its retention and is gone. */
    record Expired() implements JobStatusView {
    }

    /**
     * The idempotency reservation exists but the stream write never landed — no worker will ever
     * see this job. Not terminal: a retry carrying the same Idempotency-Key re-attempts the
     * enqueue against this same reservation (see {@code JobAcceptanceService}).
     */
    record EnqueueFailed() implements JobStatusView {
    }
}
