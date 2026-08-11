package com.example.platform.asyncredis.result;

import com.example.platform.asyncredis.api.JobKeys;
import com.example.platform.asyncredis.api.JobState;
import com.example.platform.asyncredis.api.JobStatus;
import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-06: result, status, and wakeup release as one atomic, idempotent step, and the worker ACKs
 * only after {@link ResultReleaser#release} returns without throwing. These tests exercise the real
 * Redis EVAL against a live server at {@code localhost:6379}, because the guarantee under test —
 * atomicity and idempotency of the script — is a Redis-server property, not something a mock can
 * prove.
 */
class ResultReleaserIT {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> conn;
    private static AsyncRedisProperties props;
    private static ResultReleaser releaser;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://localhost:6379");
        conn = client.connect();
        props = new AsyncRedisProperties();
        props.setResultTtl(Duration.ofSeconds(30));
        RedisConnections connections = new RedisConnections(client, props);
        releaser = new ResultReleaser(connections, ObjectMapper.getDefault(), props);
    }

    @AfterAll
    static void disconnect() {
        conn.close();
        client.shutdown();
    }

    private String jobId;

    @AfterEach
    void cleanup() {
        if (jobId != null) {
            conn.sync().del(JobKeys.result(jobId), JobKeys.status(jobId),
                    JobKeys.responseSent(jobId), JobKeys.response(jobId));
        }
    }

    private JobResult newJob() {
        jobId = UUID.randomUUID().toString();
        return new JobResult(jobId, "REL-1", 5_000L, 100L, "PROCESSED", "worker-x", 111L);
    }

    /** Simulates JobAcceptanceService.acceptNew: a job must already be PROCESSING to complete. */
    private void seedProcessingStatus(String id) {
        JobStatus status = new JobStatus(id, JobState.PROCESSING, 1L);
        conn.sync().set(JobKeys.status(id), toJson(status), SetArgs.Builder.px(60_000));
    }

    private static String toJson(Object value) {
        try {
            return ObjectMapper.getDefault().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void releaseDurablyPersistsTheResult() {
        JobResult result = newJob();
        seedProcessingStatus(jobId);

        releaser.release(result);

        String stored = conn.sync().get(JobKeys.result(jobId));
        assertEquals(toJson(result), stored, "the durable result key must hold exactly what was released");
    }

    @Test
    void releaseMarksAnAcceptedJobCompleted() {
        JobResult result = newJob();
        seedProcessingStatus(jobId);

        releaser.release(result);

        String raw = conn.sync().get(JobKeys.status(jobId));
        assertTrue(raw != null && raw.contains("\"COMPLETED\""),
                "status must move to COMPLETED; was " + raw);
    }

    @Test
    void releaseNeverResurrectsAJobThatWasNeverAccepted() {
        JobResult result = newJob();
        // No seedProcessingStatus(): this job has no acceptance record at all.

        releaser.release(result);

        assertNull(conn.sync().get(JobKeys.status(jobId)),
                "a result must not fabricate a status for a job that was never accepted");
        assertEquals(toJson(result), conn.sync().get(JobKeys.result(jobId)),
                "the result itself is still durably stored regardless of the status outcome");
    }

    @Test
    void releasePushesExactlyOneWakeupEntry() {
        JobResult result = newJob();
        seedProcessingStatus(jobId);

        releaser.release(result);

        List<String> entries = conn.sync().lrange(JobKeys.response(jobId), 0, -1);
        assertEquals(List.of(toJson(result)), entries);
    }

    @Test
    void releaseSetsATtlOnTheWakeupList() {
        JobResult result = newJob();
        seedProcessingStatus(jobId);

        releaser.release(result);

        long pttl = conn.sync().pttl(JobKeys.response(jobId));
        assertTrue(pttl > 0 && pttl <= props.getResultTtl().toMillis(),
                "the wakeup list TTL must be positive and bounded by result-ttl; was " + pttl);
    }

    @Test
    void aRedeliveredReleaseDoesNotDuplicateTheWakeup() {
        JobResult result = newJob();
        seedProcessingStatus(jobId);

        releaser.release(result);
        // A worker that crashed after release() but before ACK retries the exact same call.
        releaser.release(result);

        List<String> entries = conn.sync().lrange(JobKeys.response(jobId), 0, -1);
        assertEquals(1, entries.size(), "a redelivered release must not push a second wakeup entry");
    }

    @Test
    void aRedeliveredReleaseKeepsTheDurableResultAndStatusCoherent() {
        JobResult result = newJob();
        seedProcessingStatus(jobId);

        releaser.release(result);
        releaser.release(result);

        assertEquals(toJson(result), conn.sync().get(JobKeys.result(jobId)),
                "the result must remain exactly the released value after a redelivery");
        String status = conn.sync().get(JobKeys.status(jobId));
        assertTrue(status != null && status.contains("\"COMPLETED\""),
                "status must stay COMPLETED after a redelivery, not revert or duplicate");
    }

    @Test
    void concurrentRedeliveredReleasesStillWakeUpExactlyOnce() throws InterruptedException {
        JobResult result = newJob();
        seedProcessingStatus(jobId);

        int attempts = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        for (int i = 0; i < attempts; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    releaser.release(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "all concurrent releases must finish");

        List<String> entries = conn.sync().lrange(JobKeys.response(jobId), 0, -1);
        assertEquals(1, entries.size(),
                "the atomic EVAL must serialize concurrent releases into exactly one wakeup");
    }
}
