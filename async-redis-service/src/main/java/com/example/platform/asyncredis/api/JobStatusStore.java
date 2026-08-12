package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.redis.RedisConnections;
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

    /** Resolves what a poll can observe: unknown, processing, completed with result, or expired. */
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
