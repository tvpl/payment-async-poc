package com.example.platform.asyncredis.queue;

import com.example.platform.asyncredis.api.JobKeys;
import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.redis.RedisConnections;
import com.example.platform.asyncredis.result.ResultReleaser;
import io.lettuce.core.KeyValue;
import io.lettuce.core.XAddArgs;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Redis-only transport for the async->sync flow. The <em>queue</em> is a Redis Stream (durable,
 * consumer-group aware) and the <em>completion signal</em> is a per-request list the API blocks on
 * with BRPOP. This is what replaces Kafka + the response Kafka topic: enqueue on XADD, wait on BRPOP,
 * and fall back to a durable result key for polling.
 */
@Singleton
public class JobQueue implements JobEnqueuer {

    private static final Logger LOG = LoggerFactory.getLogger(JobQueue.class);

    public static final String FIELD_JOB_ID = "jobId";
    public static final String FIELD_REFERENCE = "reference";
    public static final String FIELD_AMOUNT = "amountCents";
    public static final String FIELD_NOTE = "note";

    private final RedisConnections redis;
    private final ObjectMapper objectMapper;
    private final AsyncRedisProperties props;
    private final ResultReleaser releaser;

    public JobQueue(RedisConnections redis, ObjectMapper objectMapper, AsyncRedisProperties props,
                    ResultReleaser releaser) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
        this.releaser = releaser;
    }

    /** Publishes the job onto the stream. Returns the stream message id. */
    @Override
    public String enqueue(String jobId, SubmitJobRequest request) {
        Map<String, String> body = new HashMap<>();
        body.put(FIELD_JOB_ID, jobId);
        body.put(FIELD_REFERENCE, request.reference());
        body.put(FIELD_AMOUNT, Long.toString(request.amountCents()));
        if (request.note() != null) {
            body.put(FIELD_NOTE, request.note());
        }
        // Approximate MAXLEN trimming bounds the stream's memory without exact-length overhead.
        XAddArgs args = XAddArgs.Builder.maxlen(props.getStreamMaxlen()).approximateTrimming();
        return redis.shared().xadd(props.getStream(), args, body);
    }

    /**
     * Blocks (on the calling virtual thread) up to {@code wait-timeout} for the worker to release the
     * result via BRPOP on the per-request list. Uses a pooled connection because BRPOP holds it.
     *
     * <p>The budget starts <strong>before</strong> the connection is acquired, not after (RED-02).
     * Acquiring is itself a queue: measuring only the BRPOP lets a request spend the full wait
     * timeout getting a connection and then the full wait timeout again on the pop, so a saturated
     * pool silently doubles the time the caller was promised. Acquisition is additionally capped at
     * {@code pool-max-wait} so a saturated pool cannot consume the entire budget queueing for a
     * connection and leave no time to actually wait for the result.
     */
    public WaitOutcome awaitResult(String jobId) {
        long deadlineNanos = System.nanoTime() + props.getWaitTimeout().toNanos();
        long acquireBudget = Math.min(remainingMillis(deadlineNanos), props.getPoolMaxWait().toMillis());

        RedisConnections.WaitLease lease;
        try {
            lease = redis.acquireWait(acquireBudget);
        } catch (NoSuchElementException e) {
            // The pool stayed saturated for the whole acquisition window: shed instead of queueing.
            LOG.debug("no wait capacity for {}: {}", jobId, e.getMessage());
            return new WaitOutcome.NoCapacity();
        } catch (Exception e) {
            LOG.warn("could not acquire a wait connection for {}: {}", jobId, e.toString());
            return new WaitOutcome.NoCapacity();
        }

        try {
            long remaining = remainingMillis(deadlineNanos);
            if (remaining <= 0) {
                return new WaitOutcome.TimedOut();
            }
            KeyValue<String, String> popped =
                    lease.sync().brpop(remaining / 1000.0, responseKey(jobId));
            if (popped == null || !popped.hasValue()) {
                return new WaitOutcome.TimedOut();
            }
            return new WaitOutcome.Released(objectMapper.readValue(popped.getValue(), JobResult.class));
        } catch (Exception e) {
            // The socket may be mid-protocol; drop it rather than hand a broken one to the next waiter.
            lease.invalidate();
            LOG.debug("await failed for {}: {}", jobId, e.getMessage());
            return new WaitOutcome.TimedOut();
        } finally {
            // Always before the catch's return value escapes: capacity is released either way.
            lease.close();
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    /** Durable lookup of a finished result (polling fallback for the 202 path). */
    public Optional<JobResult> findResult(String jobId) {
        try {
            String json = redis.shared().get(resultKey(jobId));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, JobResult.class));
        } catch (Exception e) {
            LOG.debug("result lookup failed for {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Releases the result: stores it durably (for polling), moves the job's status to terminal, and
     * pushes it to the per-request list so a blocked BRPOP wakes immediately — atomically and
     * idempotently (RED-06, see {@link ResultReleaser}). Called by the worker after processing.
     */
    public void release(JobResult result) {
        releaser.release(result);
    }

    private String responseKey(String jobId) {
        return JobKeys.response(jobId);
    }

    private String resultKey(String jobId) {
        return JobKeys.result(jobId);
    }
}
