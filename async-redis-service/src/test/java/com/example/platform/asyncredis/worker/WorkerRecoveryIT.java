package com.example.platform.asyncredis.worker;

import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.queue.JobQueue;
import io.micronaut.context.ApplicationContext;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-05: a Redis that is unreachable at startup, or that disappears mid-loop, must leave the worker
 * retrying with backoff rather than dead, and readiness must stay down until a worker can actually
 * consume again.
 *
 * <p>The application is pointed at a gate port instead of Redis itself, so "Redis is down" is a real
 * refused connection at the moment the context starts.
 */
class WorkerRecoveryIT {

    private static Map<String, Object> properties(int redisPort, String stream) {
        Map<String, Object> props = new HashMap<>();
        props.put("redis.uri", "redis://localhost:" + redisPort);
        // Bound how long a command outlives a dropped socket, so an outage surfaces as a failure
        // instead of a queued command waiting out the default timeout.
        props.put("redis.timeout", "2s");
        props.put("async.redis.security.enabled", false);
        props.put("async.redis.stream", stream);
        props.put("async.redis.group", "recovery-it.workers");
        props.put("async.redis.worker-concurrency", 1);
        props.put("async.redis.connect-backoff-min", "100ms");
        props.put("async.redis.connect-backoff-max", "500ms");
        props.put("async.redis.reclaim-interval", "200ms");
        props.put("async.redis.reclaim-lease", "2s");
        props.put("async.redis.process-latency-min-ms", 5);
        props.put("async.redis.process-latency-max-ms", 20);
        return props;
    }

    @Test
    void aWorkerSurvivesARedisThatIsDownAtStartupAndBecomesReadyWhenItReturns() throws Exception {
        String stream = "recovery-it.jobs." + UUID.randomUUID();
        try (RedisGate gate = new RedisGate(RedisGate.freePort(), 6379);
             ApplicationContext ctx = ApplicationContext.run(properties(gate.port(), stream))) {

            WorkerReadiness readiness = ctx.getBean(WorkerReadiness.class);

            // Redis refused every connection while the context came up.
            Thread.sleep(1_000);
            assertFalse(readiness.hasConsumingCapacity(),
                    "readiness must stay down while no worker can consume");
            assertEquals(0, readiness.consumingWorkers());
            assertEquals(HealthStatus.DOWN, readinessStatus(ctx));

            gate.open();

            // The worker was retrying, not dead: it must come back on its own.
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> assertTrue(readiness.hasConsumingCapacity(),
                            "worker never recovered after Redis returned"));
            assertEquals(1, readiness.consumingWorkers());
            assertEquals(HealthStatus.UP, readinessStatus(ctx));
        }
    }

    @Test
    void aRecoveredWorkerProcessesJobsAgain() throws Exception {
        String stream = "recovery-it.jobs." + UUID.randomUUID();
        try (RedisGate gate = new RedisGate(RedisGate.freePort(), 6379);
             ApplicationContext ctx = ApplicationContext.run(properties(gate.port(), stream))) {

            WorkerReadiness readiness = ctx.getBean(WorkerReadiness.class);
            gate.open();
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                    .until(readiness::hasConsumingCapacity);

            JobQueue queue = ctx.getBean(JobQueue.class);
            String jobId = UUID.randomUUID().toString();
            queue.enqueue(jobId, new SubmitJobRequest("RECOVERED-1", 3_000L, null));

            // Readiness claiming capacity has to mean the work actually gets done.
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        JobResult result = queue.findResult(jobId).orElse(null);
                        assertNotNull(result, "the recovered worker never processed the job");
                        assertEquals(jobId, result.jobId());
                        assertEquals("RECOVERED-1", result.reference());
                        assertEquals(3_000L, result.amountCents());
                        assertEquals(60L, result.feeCents()); // 2% of 3_000
                        assertEquals("PROCESSED", result.status());
                    });
        }
    }

    @Test
    void readinessGoesDownAgainWhenRedisDisappearsMidLoop() throws Exception {
        String stream = "recovery-it.jobs." + UUID.randomUUID();
        try (RedisGate gate = new RedisGate(RedisGate.freePort(), 6379);
             ApplicationContext ctx = ApplicationContext.run(properties(gate.port(), stream))) {

            WorkerReadiness readiness = ctx.getBean(WorkerReadiness.class);
            gate.open();
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                    .until(readiness::hasConsumingCapacity);

            gate.shut();

            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        assertFalse(readiness.hasConsumingCapacity(),
                                "readiness must drop when the worker loses Redis mid-loop");
                        assertEquals(HealthStatus.DOWN, readinessStatus(ctx));
                    });
        }
    }

    private static HealthStatus readinessStatus(ApplicationContext ctx) {
        HealthResult result = Mono.from(ctx.getBean(WorkerReadinessIndicator.class).getResult()).block();
        assertNotNull(result);
        return result.getStatus();
    }
}
