package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-backed acceptance state: the queryable status of a job and the idempotency reservation that
 * binds a caller-supplied key to exactly one job (RED-01, RED-08).
 *
 * <p>The status key deliberately lives longer than the result key. That is what lets a poll tell
 * "this job finished but its result has aged out" (expired) apart from "this job never existed"
 * (unknown) — once a single key carries both facts, those two cases become indistinguishable.
 */
@Singleton
public class JobStatusStore {

    private static final Logger LOG = LoggerFactory.getLogger(JobStatusStore.class);

    /**
     * Moves the status to {@code ARGV[1]} only if it is currently {@code ARGV[3]} — a single-EVAL
     * compare-and-set (AUD-03), same pattern as {@code ResultReleaser.RELEASE_SCRIPT} and
     * {@code VersionedFlagStore}'s Lua CAS. Replaces the check-then-act of a separate {@code GET}
     * (or {@link #find}) followed by an unconditioned write, which lets two concurrent callers both
     * pass the check and both act.
     *
     * <p>KEYS[1]=status ARGV[1]=new status JSON ARGV[2]=ttl millis ARGV[3]=required current state
     * name. Returns 1 if the write happened, 0 if the current state did not match (including a
     * missing/expired key, which never matches).
     */
    private static final String CAS_STATUS_LUA =
            "local cur = redis.call('get', KEYS[1]) "
                    + "if cur then "
                    + "local decoded = cjson.decode(cur) "
                    + "if decoded.state == ARGV[3] then "
                    + "redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2]) "
                    + "return 1 "
                    + "end "
                    + "end "
                    + "return 0";

    private final RedisConnections redis;
    private final ObjectMapper objectMapper;
    private final AsyncRedisProperties props;

    public JobStatusStore(RedisConnections redis, ObjectMapper objectMapper, AsyncRedisProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * Atomically binds {@code idempotencyKey} to this {@code jobId} and {@code fingerprint}.
     *
     * <p>Identity and fingerprint go in as one value under a single {@code SET NX}, so a concurrent
     * caller never observes a key that is half-associated. Same key with the same payload replays
     * the original job; same key with a different payload is a conflict, never a silent replay.
     */
    public AcceptOutcome reserve(String idempotencyKey, String jobId, String fingerprint) {
        String key = JobKeys.reservation(idempotencyKey);
        String value = write(new JobReservation(jobId, fingerprint));
        long ttlMillis = props.getIdempotencyTtl().toMillis();
        RedisCommands<String, String> commands = redis.shared();
        // Two attempts: a SET NX can lose the race to a reservation that expires between the failed
        // NX and the follow-up GET. Retrying once claims the now-free key instead of surfacing a
        // spurious failure for an astronomically narrow window.
        for (int attempt = 0; attempt < 2; attempt++) {
            if ("OK".equals(commands.set(key, value, SetArgs.Builder.nx().px(ttlMillis)))) {
                return new AcceptOutcome.Accepted(jobId);
            }
            String existing = commands.get(key);
            if (existing == null) {
                continue;
            }
            JobReservation reservation = read(existing, JobReservation.class);
            return fingerprint.equals(reservation.fingerprint())
                    ? new AcceptOutcome.Replay(reservation.jobId())
                    : new AcceptOutcome.Conflict(reservation.jobId());
        }
        throw new IllegalStateException("Failed to reserve idempotency key: " + idempotencyKey);
    }

    /** Persists the queryable {@code PROCESSING} status. Called before the job reaches the stream. */
    public void createProcessing(String jobId) {
        JobStatus status = new JobStatus(jobId, JobState.PROCESSING, System.currentTimeMillis());
        redis.shared().set(JobKeys.status(jobId), write(status),
                SetArgs.Builder.px(props.getStatusTtl().toMillis()));
    }

    // Completion is intentionally NOT exposed as a standalone method here. ResultReleaser's Lua
    // EVAL performs the same XX+KEEPTTL status write atomically together with the result write and
    // the wakeup push (RED-06); a public non-atomic alternative only invites reintroducing the
    // partial-release bug that made those three a single script in the first place.

    /**
     * Records that the reservation and PROCESSING status were persisted but the stream {@code
     * XADD} never landed — the job does not exist anywhere a worker will look. Conditioned on the
     * status still being {@code PROCESSING} (AUD-03): a release that raced ahead and completed the
     * job between the failed enqueue attempt and this call must never be overwritten back to a
     * non-terminal state. A residual window remains — this call can itself race a same-instant
     * release and lose the CAS after the release already moved past {@code PROCESSING} — but the
     * outcome is always the newer, correct terminal state, never a resurrected one.
     */
    public void markEnqueueFailed(String jobId) {
        JobStatus status = new JobStatus(jobId, JobState.ENQUEUE_FAILED, System.currentTimeMillis());
        casStatus(jobId, JobState.PROCESSING, status);
    }

    /**
     * Attempts the {@code ENQUEUE_FAILED -> PROCESSING} transition as a single CAS (AUD-03):
     * replaces the old check-then-act ({@link #find} for {@code ENQUEUE_FAILED} followed by an
     * unconditioned enqueue retry), under which two concurrent replays could both pass the check
     * and both enqueue.
     *
     * @return {@code true} when this call won the CAS and the caller must (re)attempt the enqueue;
     *         {@code false} when another concurrent replay already won it (or the status was not
     *         {@code ENQUEUE_FAILED}) — the caller must not enqueue.
     */
    public boolean tryRecoverEnqueueFailed(String jobId) {
        JobStatus status = new JobStatus(jobId, JobState.PROCESSING, System.currentTimeMillis());
        return casStatus(jobId, JobState.ENQUEUE_FAILED, status);
    }

    /**
     * Routes a job to its terminal {@code FAILED} state (AUD-13): the worker gave up on it — poison
     * (max-deliveries exceeded) or structurally malformed — and moved it to the dead-letter stream.
     * Conditioned on the status still being {@code PROCESSING}, same as {@link #markEnqueueFailed}:
     * a release that raced ahead and completed the job first must never be overwritten.
     */
    public void markFailed(String jobId) {
        JobStatus status = new JobStatus(jobId, JobState.FAILED, System.currentTimeMillis());
        casStatus(jobId, JobState.PROCESSING, status);
    }

    /** Runs {@link #CAS_STATUS_LUA}: writes {@code next} only if the current state is {@code required}. */
    private boolean casStatus(String jobId, JobState required, JobStatus next) {
        Long won = redis.shared().eval(CAS_STATUS_LUA, ScriptOutputType.INTEGER,
                new String[]{JobKeys.status(jobId)},
                write(next), Long.toString(props.getStatusTtl().toMillis()), required.name());
        return won != null && won == 1L;
    }

    /** Resolves what a poll can observe: unknown, processing, completed with result, expired, or enqueue-failed. */
    public JobStatusView find(String jobId) {
        RedisCommands<String, String> commands = redis.shared();
        String rawStatus = commands.get(JobKeys.status(jobId));
        if (rawStatus == null) {
            return new JobStatusView.Unknown();
        }
        JobStatus status = read(rawStatus, JobStatus.class);
        if (status.state() == JobState.PROCESSING) {
            return new JobStatusView.Processing();
        }
        if (status.state() == JobState.ENQUEUE_FAILED) {
            return new JobStatusView.EnqueueFailed();
        }
        if (status.state() == JobState.FAILED) {
            return new JobStatusView.Failed();
        }
        String rawResult = commands.get(JobKeys.result(jobId));
        if (rawResult == null) {
            return new JobStatusView.Expired();
        }
        return new JobStatusView.Completed(read(rawResult, JobResult.class));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            LOG.error("Failed to read {} from Redis", type.getSimpleName(), e);
            throw new IllegalStateException("Failed to deserialize " + type.getSimpleName(), e);
        }
    }
}
