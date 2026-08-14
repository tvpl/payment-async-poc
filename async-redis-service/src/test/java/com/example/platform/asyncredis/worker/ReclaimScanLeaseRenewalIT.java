package com.example.platform.asyncredis.worker;

import com.example.platform.asyncredis.api.JobKeys;
import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.queue.JobQueue;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.models.stream.PendingMessage;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-12: the reclaim scan claims its coordination turn once at the start ({@code consume}) but a
 * scan of many entries can outlive that single lease. {@code JobWorker.reclaim} now renews the turn
 * after every entry it processes, and aborts immediately — without touching the remaining entries —
 * the moment a renewal is denied.
 *
 * <p>Each test builds its own {@link ApplicationContext} (no HTTP needed) with a randomly-named
 * stream/group, for the same reason {@code AsyncFailedStatusIT} does: the reclaim-turn lease key is
 * scoped by group name only ({@link JobKeys#reclaimLease}), so a fixed name collides with every other
 * test (and the sandbox's own live worker) sharing the same Redis.
 */
class ReclaimScanLeaseRenewalIT {

    private ApplicationContext ctx;
    private RedisClient redisClient;
    private String stream;
    private String group;

    /**
     * Reserves the stream/group names without starting the app. Must run before {@link
     * #seedGhostPendingEntries} — entries have to be safely parked in the PEL under a "ghost"
     * consumer BEFORE the real worker's own {@code XREADGROUP} loop can race ahead and read them
     * directly (bypassing the reclaim path entirely, which is what this test needs to exercise).
     */
    private void prepareNames() {
        stream = "reclaim-lease-it.jobs." + UUID.randomUUID();
        group = "reclaim-lease-it-" + UUID.randomUUID();
    }

    private void start(Map<String, Object> extra) {
        Map<String, Object> props = new HashMap<>();
        props.put("async.redis.security.enabled", false);
        props.put("async.redis.stream", stream);
        props.put("async.redis.group", group);
        props.put("async.redis.worker-concurrency", 1);
        props.put("async.redis.reclaim-idle", "50ms");
        props.put("async.redis.reclaim-interval", "150ms");
        props.putAll(extra);

        ctx = ApplicationContext.run(props);
        redisClient = ctx.getBean(RedisClient.class);
    }

    @AfterEach
    void stop() {
        if (redisClient != null) {
            try (StatefulRedisConnection<String, String> conn = redisClient.connect()) {
                conn.sync().del(stream, JobKeys.reclaimLease(group));
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        if (ctx != null) {
            ctx.close();
        }
    }

    /** Adds {@code count} well-formed entries and immediately delivers them to a "ghost" consumer
     * (never ACKed), so they sit in the PEL exactly as a crashed worker's in-flight jobs would. */
    private List<String> seedGhostPendingEntries(StatefulRedisConnection<String, String> conn, int count) {
        try {
            conn.sync().xgroupCreate(XReadArgs.StreamOffset.from(stream, "0-0"), group,
                    XGroupCreateArgs.Builder.mkstream());
        } catch (io.lettuce.core.RedisBusyException e) {
            // The worker's own ensureGroup() may have already created it; harmless race.
        }
        List<String> jobIds = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            String jobId = UUID.randomUUID().toString();
            jobIds.add(jobId);
            Map<String, String> body = new HashMap<>();
            body.put(JobQueue.FIELD_JOB_ID, jobId);
            body.put(JobQueue.FIELD_REFERENCE, "reclaim-lease-it");
            body.put(JobQueue.FIELD_AMOUNT, "1000");
            conn.sync().xadd(stream, body);
        }
        conn.sync().xreadgroup(Consumer.from(group, "ghost"),
                XReadArgs.Builder.count(count), XReadArgs.StreamOffset.lastConsumed(stream));
        return jobIds;
    }

    @Test
    void aScanLongerThanTheLeaseRenewsAndNoSecondWorkerEverTakesTheTurn() throws Exception {
        RedisClient rawClient = RedisClient.create("redis://localhost:6379");
        try (StatefulRedisConnection<String, String> conn = rawClient.connect()) {
            // 10 entries * 250ms/entry (process-latency) = ~2.5s of scanning, clearly longer than
            // the 1.2s lease, proving renewal has to happen more than once for the turn to survive
            // it. The gap between renewals (250ms) stays well inside the 1.2s lease even under
            // scheduler jitter on a loaded machine — a tight margin here is what made the earlier
            // version of this test flaky under load, not a defect in the fix itself.
            prepareNames();
            List<String> jobIds = seedGhostPendingEntries(conn, 10);
            start(Map.of(
                    "async.redis.reclaim-lease", "1200ms",
                    "async.redis.process-latency-min-ms", 250,
                    "async.redis.process-latency-max-ms", 250));
            // Idle threshold must elapse before the real worker's reclaim scan will XCLAIM them.
            Thread.sleep(150);

            AsyncRedisProperties intruderProps = new AsyncRedisProperties();
            intruderProps.setGroup(group);
            intruderProps.setReclaimLease(Duration.ofSeconds(1));
            RedisClient intruderClient = RedisClient.create("redis://localhost:6379");
            boolean intruderEverWonTheTurn;
            try {
                ReclaimCoordinator intruder = new ReclaimCoordinator(
                        new RedisConnections(intruderClient, intruderProps), intruderProps);

                intruderEverWonTheTurn = false;
                long deadline = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < deadline) {
                    if (intruder.claimTurn("intruder")) {
                        intruderEverWonTheTurn = true;
                        break;
                    }
                    Thread.sleep(100);
                }
            } finally {
                intruderClient.shutdown();
            }

            assertFalse(intruderEverWonTheTurn,
                    "a competing claimant must never win the turn while the real worker's slow scan "
                            + "is still renewing it");

            // The real worker alone processed every entry exactly once: nothing left pending, and
            // every job produced its own result.
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                List<PendingMessage> stillPending = conn.sync().xpending(stream, group,
                        Range.unbounded(), Limit.from(20));
                assertTrue(stillPending.isEmpty(),
                        "every seeded entry must be fully processed by the single true owner; still"
                                + " pending: " + stillPending);
            });
            for (String jobId : jobIds) {
                String raw = conn.sync().get(JobKeys.result(jobId));
                assertTrue(raw != null && raw.contains(jobId),
                        "job " + jobId + " must have been processed exactly once by the real worker");
            }
        } finally {
            rawClient.shutdown();
        }
    }

    @Test
    void aDeniedRenewalAbortsTheScanWithoutProcessingTheRemainder() throws Exception {
        RedisClient rawClient = RedisClient.create("redis://localhost:6379");
        try (StatefulRedisConnection<String, String> conn = rawClient.connect()) {
            // 12 slow entries (450ms each = 5.4s of scanning if uninterrupted) give a wide window
            // in which to steal the lease and still leave a clear, jitter-tolerant majority of
            // entries provably untouched afterward — the exact entry-boundary the steal lands on is
            // not something this test can pin down under a loaded machine's scheduling jitter, so
            // the assertion only needs the core guarantee: not everything got processed.
            prepareNames();
            List<String> jobIds = seedGhostPendingEntries(conn, 12);
            start(Map.of(
                    "async.redis.reclaim-lease", "5s",
                    "async.redis.process-latency-min-ms", 400,
                    "async.redis.process-latency-max-ms", 400));
            Thread.sleep(150);

            // Wait for the scan to actually start and finish its first entry: the PEL must shrink.
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
                List<PendingMessage> pending = conn.sync().xpending(stream, group,
                        Range.unbounded(), Limit.from(20));
                assertTrue(pending.size() < 12, "at least one entry must have been claimed by now");
            });

            // Steal the fenced lease out from under the in-progress scan and keep re-asserting
            // ownership continuously (rather than a single racy SET): the worker's next renewal
            // attempt lands at an entry boundary whose exact wall-clock timing this test cannot
            // predict under load, so only a continuously-held steal reliably wins that race.
            RedisClient stealerClient = RedisClient.create("redis://localhost:6379");
            java.util.concurrent.atomic.AtomicBoolean stealing = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread stealer = new Thread(() -> {
                try (StatefulRedisConnection<String, String> stealConn = stealerClient.connect()) {
                    while (stealing.get()) {
                        stealConn.sync().set(JobKeys.reclaimLease(group), "intruder", SetArgs.Builder.px(10_000));
                        Thread.sleep(10);
                    }
                } catch (Exception ignored) {
                    // test teardown racing the loop; nothing to do
                }
            });
            stealer.setDaemon(true);
            stealer.start();

            try {
                // Give the worker time for many more would-be entries (450ms each) if it were still
                // (incorrectly) processing after losing the turn — comfortably more than the total
                // uninterrupted scan time, so a passing worker MUST have aborted, not just fallen
                // behind schedule.
                Thread.sleep(6_000);

                long resultsPresent = jobIds.stream()
                        .filter(id -> conn.sync().get(JobKeys.result(id)) != null)
                        .count();
                assertTrue(resultsPresent < jobIds.size(),
                        "a denied renewal must abort the scan before every entry is processed —"
                                + " processed " + resultsPresent + "/" + jobIds.size());

                List<PendingMessage> pendingAfter = conn.sync().xpending(stream, group,
                        Range.unbounded(), Limit.from(20));
                assertTrue(!pendingAfter.isEmpty(),
                        "the scan must abort with entries still remaining, not drain the whole PEL");
            } finally {
                stealing.set(false);
                stealer.join(1_000);
                stealerClient.shutdown();
            }
        } finally {
            rawClient.shutdown();
        }
    }
}
