package com.example.platform.asyncredis;

import com.example.platform.asyncredis.api.JobKeys;
import com.example.platform.asyncredis.dto.JobResponse;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.models.stream.PendingMessage;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-13: a job the worker gives up on (poison, or structurally malformed) must be an observable
 * terminal state, not a job that silently ages out from {@code 202 PROCESSING}-looking to a
 * {@code 404 UNKNOWN} once its {@code status-ttl} expires. Both DLQ code paths in {@code JobWorker}
 * — {@code deadLetterExceeded} (max-deliveries) and {@code deadLetterMalformed} — must mark the job
 * {@code FAILED} before ACKing it, and the DLQ write itself must still land before the ACK (so a
 * broken DLQ leaves the message recoverable in the PEL instead of lost).
 *
 * <p>Each test builds its own {@link ApplicationContext} with a fresh, randomly-named stream/group
 * (rather than {@code @MicronautTest} + a fixed {@code @Property} stream name), so a leftover PEL or
 * consumer-group offset from a previous run of this class can never leak into the next one — the
 * same reason {@code WorkerRecoveryIT} and {@code WorkerConsumerIdentityIT} avoid a fixed name.
 */
class AsyncFailedStatusIT {

    private EmbeddedServer server;
    private HttpClient client;
    private RedisClient redisClient;
    private String stream;
    private String dlq;
    private String group;

    private void start(Map<String, Object> extra) {
        stream = "failed-status-it.jobs." + UUID.randomUUID();
        dlq = stream + ".dlq";
        // The reclaim-turn lease key is scoped by GROUP NAME ONLY (JobKeys.reclaimLease), not by
        // stream — a fixed group name ("workers") collides with every other test class that also
        // uses it (DlqDurabilityIT, AsyncDlqIT), and a leftover lease from one can block this
        // test's own worker from ever winning its turn. A random group per test isolates it fully.
        group = "workers-" + UUID.randomUUID();
        Map<String, Object> props = new HashMap<>();
        props.put("async.redis.security.enabled", false);
        props.put("async.redis.wait-timeout", "300ms");
        props.put("async.redis.reclaim-idle", "100ms");
        props.put("async.redis.reclaim-interval", "150ms");
        props.put("async.redis.reclaim-lease", "2s");
        props.put("async.redis.process-latency-min-ms", 5);
        props.put("async.redis.process-latency-max-ms", 10);
        props.put("async.redis.stream", stream);
        props.put("async.redis.dlq-stream", dlq);
        props.put("async.redis.group", group);
        props.putAll(extra);

        server = ApplicationContext.run(EmbeddedServer.class, props);
        client = HttpClient.create(server.getURL());
        redisClient = server.getApplicationContext().getBean(RedisClient.class);
    }

    @AfterEach
    void stop() {
        // Clean up through the app's own RedisClient bean BEFORE closing the server: closing the
        // server shuts that bean's client resources down, and connecting through an already-shut-
        // down RedisClient hangs forever instead of failing fast.
        if (redisClient != null) {
            try (StatefulRedisConnection<String, String> conn = redisClient.connect()) {
                conn.sync().del(stream, dlq, "reclaim:" + group + ":owner");
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void aPoisonedJobEndsUpDeadLetteredAndPolledAsFailed() {
        start(Map.of(
                "async.redis.max-deliveries", 1,
                "async.redis.fail-on-reference", "POISON"));

        JobResponse submitted = client.toBlocking().retrieve(
                HttpRequest.POST("/jobs", new SubmitJobRequest("POISON", 1_000L, "boom")),
                JobResponse.class);

        try (StatefulRedisConnection<String, String> raw = redisClient.connect()) {
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertTrue(raw.sync().xlen(dlq) >= 1,
                            "the poison job must reach the DLQ"));
        }

        // The DLQ write and the FAILED status write both happen synchronously, in that order,
        // before the ACK (deadLetterExceeded) — once the DLQ entry is visible, the status write
        // already landed too, so a single poll (not a retry loop) proves the ordering.
        HttpResponse<JobResponse> polled = client.toBlocking().exchange(
                HttpRequest.GET("/jobs/" + submitted.jobId()), JobResponse.class);
        assertEquals(HttpStatus.OK, polled.getStatus(),
                "a dead-lettered job must be reported 200, not left pending or 404");
        assertEquals("FAILED", polled.body().status());
        assertNull(polled.body().result());
    }

    @Test
    void aMalformedJobWithAJobIdEndsUpDeadLetteredAndPolledAsFailed() {
        start(Map.of("async.redis.max-deliveries", 5));

        String jobId = UUID.randomUUID().toString();
        try (StatefulRedisConnection<String, String> raw = redisClient.connect()) {
            // A malformed entry with a jobId implies that jobId WAS genuinely accepted (a real
            // PROCESSING status exists) and only its later delivery got corrupted — markFailed is
            // conditioned on the status still being PROCESSING, so without seeding one here there
            // would be nothing to transition and GET would stay UNKNOWN, not prove anything about
            // deadLetterMalformed's own FAILED-marking.
            String statusJson = "{\"jobId\":\"" + jobId + "\",\"state\":\"PROCESSING\",\"acceptedAtEpochMs\":1}";
            raw.sync().set(JobKeys.status(jobId), statusJson);

            Map<String, String> body = new HashMap<>();
            body.put("jobId", jobId);
            body.put("reference", "bad-amount");
            body.put("amountCents", "not-a-number");
            raw.sync().xadd(stream, body);

            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertTrue(raw.sync().xlen(dlq) >= 1,
                            "the malformed job must reach the DLQ"));
        }

        HttpResponse<JobResponse> polled = client.toBlocking().exchange(
                HttpRequest.GET("/jobs/" + jobId), JobResponse.class);
        assertEquals(HttpStatus.OK, polled.getStatus());
        assertEquals("FAILED", polled.body().status());
    }

    /**
     * Mirrors {@code DlqDurabilityIT.aDlqWriteFailureLeavesTheOriginalMessageRecoverableInThePel}
     * but for the max-deliveries-exceeded path specifically: that existing test only exercises the
     * malformed path, and this fix touches both {@code deadLetterExceeded} and {@code
     * deadLetterMalformed}, so the DLQ-before-ACK ordering needs its own proof on the second path.
     */
    @Test
    void aDlqWriteFailureOnThePoisonPathLeavesTheOriginalMessageRecoverableInThePel() throws Exception {
        start(Map.of(
                "async.redis.max-deliveries", 1,
                "async.redis.fail-on-reference", "POISON"));

        try (StatefulRedisConnection<String, String> raw = redisClient.connect()) {
            raw.sync().set(dlq, "not-a-stream"); // wrong type: every XADD to it now fails

            // Must match fail-on-reference exactly (JobWorker.handle compares by equality) — a
            // unique stream per test already isolates this from other runs, so no suffix is needed.
            Thread submit = new Thread(() -> client.toBlocking().exchange(
                    HttpRequest.POST("/jobs", new SubmitJobRequest("POISON", 1_000L, "boom"))));
            submit.start();

            // While the DLQ is broken, the poisoned entry must stay pending (un-ACKed) across
            // several reclaim cycles, never silently dropped.
            Thread.sleep(600);
            List<PendingMessage> stillPending =
                    raw.sync().xpending(stream, group, Range.unbounded(), Limit.from(10));
            assertTrue(!stillPending.isEmpty(),
                    "a poison message must stay in the PEL, recoverable, while its DLQ write fails");
            for (PendingMessage pm : stillPending) {
                assertTrue(pm.getRedeliveryCount() >= 1);
            }

            raw.sync().del(dlq);
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                    .until(() -> raw.sync().xlen(dlq) >= 1);
        }
    }
}
