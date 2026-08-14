package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.queue.JobEnqueuer;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * task_ba7dd0af: if {@code XADD} fails right after the idempotency reservation is taken, the
 * jobId is never really accepted — but a naive read of the reservation alone would keep returning
 * {@code Replay} against a job nothing will ever process, and the original caller would see a
 * bare 500 leaking the Lettuce failure. Proves the fix end to end: the failed attempt is recorded
 * as {@code ENQUEUE_FAILED}, the caller sees a typed {@link JobEnqueueException}, and a retry
 * carrying the same Idempotency-Key re-attempts the SAME reservation instead of trusting the
 * stale {@code Replay}.
 */
class JobAcceptanceServiceEnqueueFailureUnitTest {

    private static final SubmitJobRequest REQUEST = new SubmitJobRequest("ENQ-1", 5_000L, null);

    /** In-memory stand-in covering reservation + status, including the {@code ENQUEUE_FAILED} state. */
    private static final class FakeStore extends JobStatusStore {

        private final Map<String, String> reservedJobId = new HashMap<>();
        private final Map<String, String> reservedFingerprint = new HashMap<>();
        private final Map<String, JobStatusView> status = new HashMap<>();

        private FakeStore() {
            super(new RedisConnections(null, new AsyncRedisProperties()),
                    ObjectMapper.getDefault(), new AsyncRedisProperties());
        }

        @Override
        public AcceptOutcome reserve(String idempotencyKey, String jobId, String fingerprint) {
            String existingJobId = reservedJobId.get(idempotencyKey);
            if (existingJobId == null) {
                reservedJobId.put(idempotencyKey, jobId);
                reservedFingerprint.put(idempotencyKey, fingerprint);
                return new AcceptOutcome.Accepted(jobId);
            }
            return fingerprint.equals(reservedFingerprint.get(idempotencyKey))
                    ? new AcceptOutcome.Replay(existingJobId)
                    : new AcceptOutcome.Conflict(existingJobId);
        }

        @Override
        public void createProcessing(String jobId) {
            status.put(jobId, new JobStatusView.Processing());
        }

        @Override
        public void markEnqueueFailed(String jobId) {
            status.put(jobId, new JobStatusView.EnqueueFailed());
        }

        @Override
        public boolean tryRecoverEnqueueFailed(String jobId) {
            if (status.get(jobId) instanceof JobStatusView.EnqueueFailed) {
                status.put(jobId, new JobStatusView.Processing());
                return true;
            }
            return false;
        }

        @Override
        public JobStatusView find(String jobId) {
            return status.getOrDefault(jobId, new JobStatusView.Unknown());
        }
    }

    /** Fails every call up to and including {@code failUntilCallInclusive}, then succeeds. */
    private static final class FlakyEnqueuer implements JobEnqueuer {
        private final int failUntilCallInclusive;
        private int calls = 0;

        private FlakyEnqueuer(int failUntilCallInclusive) {
            this.failUntilCallInclusive = failUntilCallInclusive;
        }

        @Override
        public String enqueue(String jobId, SubmitJobRequest request) {
            calls++;
            if (calls <= failUntilCallInclusive) {
                throw new RuntimeException("simulated Redis XADD failure");
            }
            return "0-" + calls;
        }
    }

    @Test
    void anEnqueueFailureAfterReservationIsRecordedAndSurfacedNotSwallowed() {
        FakeStore store = new FakeStore();
        JobAcceptanceService service = new JobAcceptanceService(store, new FlakyEnqueuer(1));

        JobEnqueueException failure = assertThrows(JobEnqueueException.class,
                () -> service.accept("idem-1", REQUEST));

        assertEquals(new JobStatusView.EnqueueFailed(), store.find(failure.jobId()));
    }

    @Test
    void aReplayAgainstAnEnqueueFailedJobRetriesTheSameReservationInsteadOfTrustingTheStaleReplay() {
        FakeStore store = new FakeStore();
        FlakyEnqueuer enqueuer = new FlakyEnqueuer(1);
        JobAcceptanceService service = new JobAcceptanceService(store, enqueuer);

        JobEnqueueException firstFailure = assertThrows(JobEnqueueException.class,
                () -> service.accept("idem-2", REQUEST));

        AcceptOutcome retryOutcome = service.accept("idem-2", REQUEST);

        String retriedJobId = assertInstanceOf(AcceptOutcome.Accepted.class, retryOutcome).jobId();
        assertEquals(firstFailure.jobId(), retriedJobId,
                "retry must reuse the SAME reservation, not mint a new job the client never asked for");
        assertEquals(new JobStatusView.Processing(), store.find(retriedJobId));
        assertEquals(2, enqueuer.calls);
    }

    @Test
    void aConflictingPayloadIsStillReportedAsConflictEvenAfterTheOriginalFailedToEnqueue() {
        FakeStore store = new FakeStore();
        FlakyEnqueuer enqueuer = new FlakyEnqueuer(1);
        JobAcceptanceService service = new JobAcceptanceService(store, enqueuer);

        assertThrows(JobEnqueueException.class, () -> service.accept("idem-3", REQUEST));

        SubmitJobRequest differentPayload = new SubmitJobRequest("DIFFERENT", 1L, null);
        AcceptOutcome outcome = service.accept("idem-3", differentPayload);

        assertInstanceOf(AcceptOutcome.Conflict.class, outcome);
        assertEquals(1, enqueuer.calls, "a conflicting fingerprint must never trigger a retry enqueue");
    }
}
