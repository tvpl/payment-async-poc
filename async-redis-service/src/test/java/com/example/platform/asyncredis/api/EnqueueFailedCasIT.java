package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.queue.JobEnqueuer;
import com.example.platform.asyncredis.redis.RedisConnections;
import com.example.platform.asyncredis.result.ResultReleaser;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-03: the {@code ENQUEUE_FAILED -> PROCESSING} transition used by a replay is a single-EVAL CAS,
 * not check-then-act. Runs against the real Redis at {@code localhost:6379}, because the guarantee
 * under test — exactly one of several concurrent callers wins the transition — is a property of the
 * Lua script's atomicity, not something a mock can prove.
 */
class EnqueueFailedCasIT {

    private static final SubmitJobRequest REQUEST = new SubmitJobRequest("CAS-1", 5_000L, null);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> conn;
    private static AsyncRedisProperties props;
    private static RedisConnections connections;
    private static JobStatusStore store;
    private static String stream;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://localhost:6379");
        conn = client.connect();
        stream = "cas-it.jobs." + UUID.randomUUID();
        props = new AsyncRedisProperties();
        props.setStatusTtl(Duration.ofSeconds(60));
        props.setStream(stream);
        connections = new RedisConnections(client, props);
        store = new JobStatusStore(connections, ObjectMapper.getDefault(), props);
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
            conn.sync().del(JobKeys.status(jobId), JobKeys.result(jobId));
        }
        conn.sync().del(stream);
    }

    /** Enqueuer that fails exactly once (the original attempt), then does a real XADD on every retry. */
    private static final class RealAfterFirstFailureEnqueuer implements JobEnqueuer {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String enqueue(String id, SubmitJobRequest request) {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("simulated XADD failure");
            }
            Map<String, String> body = new HashMap<>();
            body.put("jobId", id);
            return conn.sync().xadd(stream, body);
        }
    }

    @Test
    void concurrentReplaysOfTheSameEnqueueFailedJobProduceExactlyOneXadd() throws InterruptedException {
        jobId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        RealAfterFirstFailureEnqueuer enqueuer = new RealAfterFirstFailureEnqueuer();
        JobAcceptanceService service = new JobAcceptanceService(store, enqueuer);

        // First attempt fails to enqueue: the reservation exists but the job is ENQUEUE_FAILED.
        JobEnqueueException first = assertThrows(JobEnqueueException.class,
                () -> service.accept(idempotencyKey, REQUEST));
        jobId = first.jobId();
        assertEquals(new JobStatusView.EnqueueFailed(), store.find(jobId));

        int attempts = 12;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < attempts; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    if (service.accept(idempotencyKey, REQUEST) instanceof AcceptOutcome.Accepted) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "all concurrent replays must finish");

        assertEquals(1, accepted.get(),
                "exactly one concurrent replay must win the CAS and become Accepted");
        assertEquals(1L, conn.sync().xlen(stream),
                "exactly one XADD must land on the stream for the recovered job");
        assertEquals(new JobStatusView.Processing(), store.find(jobId));
    }

    @Test
    void markEnqueueFailedAfterTheWorkerAlreadyReleasedLeavesCompletedIntact() {
        jobId = UUID.randomUUID().toString();
        store.createProcessing(jobId);

        ResultReleaser releaser = new ResultReleaser(connections, ObjectMapper.getDefault(), props);
        JobResult result = new JobResult(jobId, "CAS-2", 7_000L, 140L, "PROCESSED", "worker-x", 111L);
        releaser.release(result);
        assertInstanceOf(JobStatusView.Completed.class, store.find(jobId));

        // A stale enqueue-failure signal arrives after the worker already completed the job (e.g.
        // the XADD failure was reported late by a slow client, or the two raced).
        store.markEnqueueFailed(jobId);

        JobStatusView after = store.find(jobId);
        JobStatusView.Completed completed = assertInstanceOf(JobStatusView.Completed.class, after,
                "COMPLETED must be preserved; markEnqueueFailed must not resurrect a terminal job");
        assertEquals(result, completed.result(), "GET must still return the original result");
    }
}
