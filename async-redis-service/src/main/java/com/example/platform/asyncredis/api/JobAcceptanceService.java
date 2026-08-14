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
 *
 * <p>The reservation and the enqueue are not one atomic step: if {@code XADD} fails after the key
 * is already reserved, that jobId is never really accepted, but a naive read of the reservation
 * would keep returning {@code Replay} against it forever — a black hole indistinguishable from a
 * slow worker. {@link JobStatusStore#markEnqueueFailed} records the true state, and a replay that
 * lands on an {@code ENQUEUE_FAILED} jobId retries the enqueue against the SAME reservation
 * instead of trusting a stale {@code Replay}.
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
     * @throws JobEnqueueException if the reservation (and status, for a fresh jobId) were
     *     persisted but the stream write failed — the caller gets 503, and a retry with the same
     *     Idempotency-Key will re-attempt the enqueue instead of finding nothing
     */
    public AcceptOutcome accept(@Nullable String idempotencyKey, SubmitJobRequest request) {
        String jobId = UUID.randomUUID().toString();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return attemptEnqueue(jobId, request);
        }
        AcceptOutcome outcome = store.reserve(idempotencyKey, jobId, JobFingerprint.of(request));
        if (outcome instanceof AcceptOutcome.Accepted accepted) {
            return attemptEnqueue(accepted.jobId(), request);
        }
        if (outcome instanceof AcceptOutcome.Replay replay && store.tryRecoverEnqueueFailed(replay.jobId())) {
            // Won the CAS: this call alone is responsible for the retry. A concurrent replay that
            // loses the CAS falls through to `return outcome` below and enqueues nothing (AUD-03).
            return enqueueAfterStatusSet(replay.jobId(), request);
        }
        // Replay (against a job that DID enqueue, or that another concurrent replay just recovered)
        // and Conflict both enqueue nothing: the key already owns a job.
        return outcome;
    }

    private AcceptOutcome attemptEnqueue(String jobId, SubmitJobRequest request) {
        store.createProcessing(jobId);
        return enqueueAfterStatusSet(jobId, request);
    }

    /** Enqueues a job whose {@code PROCESSING} status is already persisted, by whichever caller won it. */
    private AcceptOutcome enqueueAfterStatusSet(String jobId, SubmitJobRequest request) {
        try {
            enqueuer.enqueue(jobId, request);
        } catch (RuntimeException e) {
            store.markEnqueueFailed(jobId);
            throw new JobEnqueueException(jobId, e);
        }
        return new AcceptOutcome.Accepted(jobId);
    }
}
