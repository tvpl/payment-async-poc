package com.example.platform.asyncredis.queue;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.lettuce.core.KeyValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Redis-only transport for the async->sync flow. The <em>queue</em> is a Redis Stream (durable,
 * consumer-group aware) and the <em>completion signal</em> is a per-request list the API blocks on
 * with BRPOP. This is what replaces Kafka + the response Kafka topic: enqueue on XADD, wait on BRPOP,
 * and fall back to a durable result key for polling.
 */
@Singleton
public class JobQueue {

    private static final Logger LOG = LoggerFactory.getLogger(JobQueue.class);

    static final String FIELD_JOB_ID = "jobId";
    static final String FIELD_REFERENCE = "reference";
    static final String FIELD_AMOUNT = "amountCents";
    static final String FIELD_NOTE = "note";

    private final RedisConnections redis;
    private final ObjectMapper objectMapper;
    private final AsyncRedisProperties props;

    public JobQueue(RedisConnections redis, ObjectMapper objectMapper, AsyncRedisProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /** Publishes the job onto the stream. Returns the stream message id. */
    public String enqueue(String jobId, SubmitJobRequest request) {
        Map<String, String> body = new HashMap<>();
        body.put(FIELD_JOB_ID, jobId);
        body.put(FIELD_REFERENCE, request.reference());
        body.put(FIELD_AMOUNT, Long.toString(request.amountCents()));
        if (request.note() != null) {
            body.put(FIELD_NOTE, request.note());
        }
        return redis.shared().xadd(props.getStream(), body);
    }

    /**
     * Blocks (on the calling virtual thread) up to {@code wait-timeout} for the worker to release the
     * result via BRPOP on the per-request list. Uses a dedicated connection because BRPOP holds it.
     *
     * @return the result if released in time, otherwise empty (caller returns 202).
     */
    public Optional<JobResult> awaitResult(String jobId) {
        double timeoutSeconds = props.getWaitTimeout().toMillis() / 1000.0;
        try (StatefulRedisConnection<String, String> conn = redis.dedicated()) {
            KeyValue<String, String> popped = conn.sync().brpop(timeoutSeconds, responseKey(jobId));
            if (popped == null || !popped.hasValue()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(popped.getValue(), JobResult.class));
        } catch (Exception e) {
            LOG.debug("await failed for {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
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
     * Releases the result: stores it durably (for polling) and pushes it to the per-request list so a
     * blocked BRPOP wakes immediately. Called by the worker after processing.
     */
    public void release(JobResult result) {
        try {
            RedisCommands<String, String> c = redis.shared();
            String json = objectMapper.writeValueAsString(result);
            long ttlMs = props.getResultTtl().toMillis();
            c.psetex(resultKey(result.jobId()), ttlMs, json);
            c.lpush(responseKey(result.jobId()), json);
            // TTL on the response list so an un-awaited (202) job doesn't leak the key.
            c.pexpire(responseKey(result.jobId()), ttlMs);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to release result for " + result.jobId(), e);
        }
    }

    private String responseKey(String jobId) {
        return "resp:" + jobId;
    }

    private String resultKey(String jobId) {
        return "job:" + jobId + ":result";
    }
}
