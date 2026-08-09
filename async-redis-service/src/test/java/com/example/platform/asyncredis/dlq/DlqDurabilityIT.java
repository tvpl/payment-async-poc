package com.example.platform.asyncredis.dlq;

import com.example.platform.asyncredis.dto.SubmitJobRequest;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.models.stream.PendingMessage;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-07 end to end, against the real worker and a real Redis: exactly {@code max-deliveries}
 * attempts occur before a poison job reaches the DLQ (the off-by-one fix), a malformed message is
 * dead-lettered with its reason preserved instead of silently ACKed, and a DLQ write failure leaves
 * the original message recoverable - pending, not ACKed - rather than lost.
 */
@MicronautTest
@Property(name = "async.redis.security.enabled", value = "false")
@Property(name = "async.redis.wait-timeout", value = "500ms")
@Property(name = "async.redis.max-deliveries", value = "3")
@Property(name = "async.redis.reclaim-idle", value = "100ms")
@Property(name = "async.redis.reclaim-interval", value = "150ms")
@Property(name = "async.redis.process-latency-min-ms", value = "5")
@Property(name = "async.redis.process-latency-max-ms", value = "10")
@Property(name = "async.redis.fail-on-reference", value = "POISON")
@Property(name = "async.redis.stream", value = DlqDurabilityIT.STREAM)
@Property(name = "async.redis.dlq-stream", value = DlqDurabilityIT.DLQ)
class DlqDurabilityIT {

    static final String STREAM = "dlq-durability-it.jobs";
    static final String DLQ = "dlq-durability-it.jobs.dlq";
    private static final String GROUP = "workers";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    RedisClient redisClient;

    @Test
    void exactlyMaxDeliveriesAttemptsOccurBeforePoisonReachesDlq() {
        // Fired in the background: the API blocks up to wait-timeout for a result that never comes,
        // and by then this poison job (fast latencies, fast reclaim) could already be fully DLQ'd -
        // polling has to start immediately, not after the HTTP call returns.
        Thread submit = new Thread(() -> client.toBlocking().exchange(HttpRequest.POST("/jobs",
                new SubmitJobRequest("POISON", 1_000L, "boom"))));
        submit.start();

        try (StatefulRedisConnection<String, String> raw = redisClient.connect()) {
            long[] maxObserved = {0};
            // Poll the real PEL every 15ms, tracking the highest delivery count Redis itself reports,
            // until the DLQ actually receives the entry - a single loop, so no observation window
            // between "check the PEL" and "check the DLQ" can let a fast completion slip past unseen.
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(15)).untilAsserted(() -> {
                List<PendingMessage> pending = raw.sync().xpending(STREAM, GROUP, Range.unbounded(),
                        Limit.from(10));
                for (PendingMessage pm : pending) {
                    maxObserved[0] = Math.max(maxObserved[0], pm.getRedeliveryCount());
                }
                assertTrue(raw.sync().xlen(DLQ) >= 1, "the poison job must reach the DLQ");
            });

            assertEquals(3, maxObserved[0],
                    "max-deliveries=3 must allow exactly 3 attempts, no off-by-one; observed "
                            + maxObserved[0]);
        }
    }

    @Test
    void malformedMessageMissingJobIdIsDeadLetteredWithReasonInsteadOfSilentlyAcked() {
        try (StatefulRedisConnection<String, String> raw = redisClient.connect()) {
            long dlqLenBefore = safeLen(raw, DLQ);
            Map<String, String> body = new HashMap<>();
            body.put("reference", "no-jobid-" + UUID.randomUUID());
            body.put("amountCents", "500");
            raw.sync().xadd(STREAM, body);

            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                    assertTrue(dlqEntryWithReason(raw, "reference", body.get("reference"), "missing-job-id"),
                            "a job with no jobId must be dead-lettered with reason missing-job-id"));

            assertTrue(raw.sync().xlen(DLQ) > dlqLenBefore, "the DLQ must have grown, not lost the entry");
        }
    }

    @Test
    void malformedMessageWithInvalidAmountIsDeadLetteredWithReason() {
        try (StatefulRedisConnection<String, String> raw = redisClient.connect()) {
            String jobId = UUID.randomUUID().toString();
            Map<String, String> body = new HashMap<>();
            body.put("jobId", jobId);
            body.put("reference", "bad-amount");
            body.put("amountCents", "not-a-number");
            raw.sync().xadd(STREAM, body);

            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                    assertTrue(dlqEntryWithReason(raw, "jobId", jobId, "invalid-amount"),
                            "an unparseable amountCents must be dead-lettered with reason invalid-amount"));
        }
    }

    @Test
    void aDlqWriteFailureLeavesTheOriginalMessageRecoverableInThePel() throws Exception {
        try (StatefulRedisConnection<String, String> raw = redisClient.connect()) {
            // Force every XADD to the DLQ stream to fail: a plain string key is the wrong type for it.
            raw.sync().del(DLQ);
            raw.sync().set(DLQ, "not-a-stream");
            try {
                Map<String, String> body = new HashMap<>();
                body.put("reference", "no-jobid-blocked-" + UUID.randomUUID());
                body.put("amountCents", "500");
                String entryId = raw.sync().xadd(STREAM, body);

                // While the DLQ is broken, the entry must stay pending (un-ACKed), never silently
                // dropped, across several reclaim cycles - not just the very first attempt.
                Thread.sleep(500);
                List<PendingMessage> stillPending = raw.sync().xpending(STREAM, GROUP,
                        Range.create(entryId, entryId), Limit.from(1));
                assertEquals(1, stillPending.size(),
                        "a message must stay in the PEL, recoverable, while its DLQ write keeps failing");

                // Unblock the DLQ: the same message must now drain through to it and finally get ACKed.
                raw.sync().del(DLQ);
                await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(() ->
                        raw.sync().xpending(STREAM, GROUP, Range.create(entryId, entryId), Limit.from(1))
                                .isEmpty());
                // Which exact reason wins depends on how many redeliveries piled up while the DLQ was
                // blocked (missing-job-id if still caught in handle(), max-deliveries-exceeded if the
                // outage outlasted max-deliveries) - both are legitimate, so only the "recoverable with
                // a reason" guarantee is asserted, not one specific string.
                assertTrue(dlqEntryHasAnyReason(raw, "reference", body.get("reference")),
                        "once the DLQ recovers, the previously blocked message must land there with a"
                                + " reason preserved, not vanish");
            } finally {
                raw.sync().del(DLQ);
            }
        }
    }

    private static long safeLen(StatefulRedisConnection<String, String> raw, String key) {
        try {
            return raw.sync().xlen(key);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean dlqEntryHasAnyReason(StatefulRedisConnection<String, String> raw, String matchField,
                                                String matchValue) {
        List<StreamMessage<String, String>> entries = raw.sync().xrange(DLQ, Range.unbounded());
        for (StreamMessage<String, String> entry : entries) {
            if (matchValue.equals(entry.getBody().get(matchField))) {
                String reason = entry.getBody().get(DeadLetterWriter.FIELD_REASON);
                return reason != null && !reason.isBlank();
            }
        }
        return false;
    }

    private static boolean dlqEntryWithReason(StatefulRedisConnection<String, String> raw, String matchField,
                                              String matchValue, String expectedReason) {
        List<StreamMessage<String, String>> entries = raw.sync().xrange(DLQ, Range.unbounded());
        for (StreamMessage<String, String> entry : entries) {
            if (matchValue.equals(entry.getBody().get(matchField))) {
                return expectedReason.equals(entry.getBody().get(DeadLetterWriter.FIELD_REASON));
            }
        }
        return false;
    }
}
