package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.queue.JobEnqueuer;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.UUID;

/**
 * Accepts a job: reserve the idempotency key, persist a queryable status, then enqueue — in that
 * order (RED-01, RED-08).
 *
 * <p>The order is the point. Enqueuing first opens a window where a worker can finish and a poll
 * can still find nothing, so an accepted job would briefly look like one that never existed.
 */
@Singleton
public class JobAcceptanceService {

    private final JobStatusStore store;
    private final JobEnqueuer enqueuer;

    public JobAcceptanceService(JobStatusStore store, JobEnqueuer enqueuer) {
        this.store = store;
        this.enqueuer = enqueuer;
    }

    /**
     * @param idempotencyKey caller-supplied key, or {@code null} when the caller opts out of dedup
     * @return what happened: a new job, a replay of the original, or a conflict
     */
    public AcceptOutcome accept(@Nullable String idempotencyKey, SubmitJobRequest request) {
        String jobId = UUID.randomUUID().toString();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return acceptNew(jobId, request);
        }
        AcceptOutcome outcome = store.reserve(idempotencyKey, jobId, JobFingerprint.of(request));
        if (outcome instanceof AcceptOutcome.Accepted accepted) {
            return acceptNew(accepted.jobId(), request);
        }
        // Replay and Conflict both enqueue nothing: the key already owns a job.
        return outcome;
    }

    private AcceptOutcome acceptNew(String jobId, SubmitJobRequest request) {
        store.createProcessing(jobId);
        enqueuer.enqueue(jobId, request);
        return new AcceptOutcome.Accepted(jobId);
    }
}
