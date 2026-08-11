package com.example.platform.asyncredis.result;

import com.example.platform.asyncredis.api.JobKeys;
import com.example.platform.asyncredis.api.JobState;
import com.example.platform.asyncredis.api.JobStatus;
import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.lettuce.core.ScriptOutputType;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

/**
 * Releases a worker's finished result: durable persistence, the terminal status, and the wakeup for
 * a blocked waiter — as one atomic, idempotent step (RED-06).
 *
 * <p>The previous release wrote result, status, and wakeup as four separate round trips. A connection
 * lost between any two of them left the job inconsistent — most visibly, a result durably stored but
 * a status stuck on {@code PROCESSING} forever, because polling ({@link
 * com.example.platform.asyncredis.api.JobStatusStore#find}) trusts the status key, not the result
 * key, to know a job is done. The worker only ACKs after {@link #release} returns without throwing
 * ({@code JobWorker.handle}), so any failure here leaves the message in the PEL to be redelivered,
 * which calls this again for the same job (id and result content are the same on redelivery; only
 * {@code processedAtEpochMs} / {@code processedBy} can differ across attempts). That has to be safe:
 * a second, unconditioned {@code LPUSH} would leave a stale duplicate sitting in the wakeup list for
 * whichever caller pops it next.
 *
 * <p>Everything below runs inside a single {@code EVAL}. Redis executes a script as one indivisible
 * step (see <a href="https://redis.io/docs/latest/develop/interact/programmability/eval-intro/">EVAL
 * / atomicity of scripts</a>): no other command interleaves, and a client-side crash or dropped
 * connection can only ever observe "before" or "after", never a script half-applied.
 */
@Singleton
public class ResultReleaser {

    /**
     * KEYS[1]=result KEYS[2]=status KEYS[3]=wakeup-marker KEYS[4]=response(wakeup list)
     * ARGV[1]=result JSON ARGV[2]=ttl millis ARGV[3]=status JSON (COMPLETED)
     *
     * <p>The result is written unconditionally every call: redeliveries carry the same content, so
     * overwriting it is harmless and keeps its TTL anchored to the most recent release attempt. The
     * status update is {@code XX}+{@code KEEPTTL}, matching {@code JobStatusStore.markCompleted}: only
     * a job that was actually accepted can complete, and completing one never resets its own expiry.
     * The marker is the idempotency gate — only its first {@code SET NX} triggers the wakeup, so a
     * redelivered release cannot push a second copy onto the response list.
     */
    private static final String RELEASE_SCRIPT =
            "redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2]) "
                    + "redis.call('set', KEYS[2], ARGV[3], 'XX', 'KEEPTTL') "
                    + "if redis.call('set', KEYS[3], '1', 'NX', 'PX', ARGV[2]) then "
                    + "redis.call('lpush', KEYS[4], ARGV[1]) "
                    + "redis.call('pexpire', KEYS[4], ARGV[2]) "
                    + "return 1 "
                    + "end "
                    + "return 0";

    private final RedisConnections redis;
    private final ObjectMapper objectMapper;
    private final AsyncRedisProperties props;

    public ResultReleaser(RedisConnections redis, ObjectMapper objectMapper, AsyncRedisProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * Releases {@code result}. Idempotent: safe to call more than once for the same job, which is
     * exactly what a PEL redelivery after a partial failure does. Throws on any Redis-level failure —
     * the caller's signal not to ACK.
     */
    public void release(JobResult result) {
        try {
            String resultJson = write(result);
            String statusJson =
                    write(new JobStatus(result.jobId(), JobState.COMPLETED, System.currentTimeMillis()));
            String ttlMs = Long.toString(props.getResultTtl().toMillis());
            redis.shared().eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{
                            JobKeys.result(result.jobId()),
                            JobKeys.status(result.jobId()),
                            JobKeys.responseSent(result.jobId()),
                            JobKeys.response(result.jobId())
                    },
                    resultJson, ttlMs, statusJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to release result for " + result.jobId(), e);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize " + value.getClass().getSimpleName(), e);
        }
    }
}
