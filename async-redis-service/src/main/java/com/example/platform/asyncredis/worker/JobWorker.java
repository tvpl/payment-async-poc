package com.example.platform.asyncredis.worker;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.metrics.AsyncMetrics;
import com.example.platform.asyncredis.queue.JobQueue;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.PendingMessage;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Consumes jobs from the Redis Stream with a consumer group and releases results. Each worker runs a
 * blocking {@code XREADGROUP} loop on its own dedicated connection, under a consumer name unique to
 * this process (RED-04, see {@link WorkerIdentity}).
 *
 * <p>Because the group tracks a Pending Entries List, a crashed worker's in-flight jobs are not lost.
 * {@link #reclaim} inspects pending entries and either re-claims those idle beyond {@code
 * reclaim-idle} or, once a job has been delivered more than {@code max-deliveries} times, moves it to
 * the dead-letter stream. Only the worker holding the {@link ReclaimCoordinator} turn scans, so
 * workers do not race each other to claim the same ids.
 *
 * <p>The connection is (re)established inside the loop with capped exponential backoff (RED-05): a
 * Redis that is down at startup, or that disappears mid-loop, leaves the worker retrying rather than
 * dead, and {@link WorkerReadiness} keeps readiness down until a worker actually reads from the
 * group again.
 */
@Context
public class JobWorker {

    private static final Logger LOG = LoggerFactory.getLogger(JobWorker.class);
    private static final String FIELD_DLQ_REASON = "dlqReason";

    private final RedisConnections redis;
    private final JobQueue queue;
    private final AsyncRedisProperties props;
    private final WorkerIdentity identity;
    private final ReclaimCoordinator coordinator;
    private final WorkerReadiness readiness;
    @Nullable
    private final AsyncMetrics metrics;

    private final Thread[] threads;
    private volatile boolean running = true;

    public JobWorker(RedisConnections redis, JobQueue queue, AsyncRedisProperties props,
                     WorkerIdentity identity, ReclaimCoordinator coordinator,
                     WorkerReadiness readiness, @Nullable AsyncMetrics metrics) {
        this.redis = redis;
        this.queue = queue;
        this.props = props;
        this.identity = identity;
        this.coordinator = coordinator;
        this.readiness = readiness;
        this.metrics = metrics;
        this.threads = new Thread[Math.max(1, props.getWorkerConcurrency())];
    }

    @PostConstruct
    void start() {
        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            Thread t = new Thread(() -> runLoop(index), "async-worker-" + i);
            t.setDaemon(true);
            threads[i] = t;
            t.start();
        }
        LOG.info("Started {} async worker(s) as instance {} on stream {}",
                threads.length, identity.instanceId(), props.getStream());
    }

    /**
     * Connect, consume, and on any connection-level failure back off and connect again. Nothing here
     * touches Redis before the loop body, so an unreachable Redis at startup costs a retry rather
     * than the worker thread.
     */
    private void runLoop(int index) {
        String consumerName = identity.consumerName(index);
        long backoffMs = props.getConnectBackoffMin().toMillis();
        long maxBackoffMs = props.getConnectBackoffMax().toMillis();

        while (running) {
            long connectedAt = 0;
            try (StatefulRedisConnection<String, String> conn = redis.dedicated()) {
                RedisCommands<String, String> c = conn.sync();
                ensureGroup(c);
                connectedAt = System.currentTimeMillis();
                consume(c, consumerName);
            } catch (Exception e) {
                if (running) {
                    LOG.warn("worker {} lost its connection: {}", consumerName, e.toString());
                }
            } finally {
                // Capacity is gone the moment the connection is: readiness must say so.
                readiness.markUnavailable(consumerName);
                coordinator.releaseTurn(consumerName);
            }

            if (!running) {
                break;
            }
            // A connection that lived longer than the ceiling was healthy, so the next outage starts
            // over at the short delay instead of inheriting a long backoff from an old incident.
            boolean wasHealthy = connectedAt > 0
                    && System.currentTimeMillis() - connectedAt >= maxBackoffMs;
            sleep(backoffMs);
            backoffMs = wasHealthy
                    ? props.getConnectBackoffMin().toMillis()
                    : Math.min(maxBackoffMs, backoffMs * 2);
        }
        readiness.markUnavailable(consumerName);
        LOG.info("worker {} stopped", consumerName);
    }

    /** Reads and handles messages until the connection fails or the service shuts down. */
    private void consume(RedisCommands<String, String> c, String consumerName) {
        long blockMs = Math.min(2000, Math.max(100, props.getReclaimInterval().toMillis()));
        long lastReclaim = 0;

        while (running) {
            long now = System.currentTimeMillis();
            if (now - lastReclaim >= props.getReclaimInterval().toMillis()) {
                lastReclaim = now;
                // One scanner at a time: concurrent scans redeliver the same entry to several
                // workers and inflate its delivery count toward the DLQ.
                if (coordinator.claimTurn(consumerName)) {
                    reclaim(c, consumerName);
                }
            }
            List<StreamMessage<String, String>> messages = c.xreadgroup(
                    Consumer.from(props.getGroup(), consumerName),
                    XReadArgs.Builder.block(blockMs).count(16),
                    XReadArgs.StreamOffset.lastConsumed(props.getStream()));
            // A completed read is the proof of consuming capacity readiness reports.
            readiness.markConsuming(consumerName);
            if (messages != null) {
                for (StreamMessage<String, String> message : messages) {
                    handle(c, message);
                }
            }
        }
    }

    private void ensureGroup(RedisCommands<String, String> c) {
        try {
            c.xgroupCreate(
                    XReadArgs.StreamOffset.from(props.getStream(), "0-0"),
                    props.getGroup(),
                    XGroupCreateArgs.Builder.mkstream());
        } catch (RedisBusyException e) {
            // BUSYGROUP: the group already exists, which is the normal case after the first start.
            LOG.debug("consumer group {} already exists", props.getGroup());
        }
    }

    /**
     * Inspects the group's pending entries: poison jobs (delivered too many times) go to the DLQ;
     * entries idle beyond {@code reclaim-idle} are claimed by this worker and re-processed.
     */
    private void reclaim(RedisCommands<String, String> c, String consumerName) {
        try {
            List<PendingMessage> pending = c.xpending(props.getStream(), props.getGroup(),
                    Range.unbounded(), Limit.from(100));
            for (PendingMessage pm : pending) {
                if (pm.getRedeliveryCount() > props.getMaxDeliveries()) {
                    deadLetter(c, pm.getId());
                } else if (pm.getMsSinceLastDelivery() >= props.getReclaimIdle().toMillis()) {
                    List<StreamMessage<String, String>> claimed = c.xclaim(props.getStream(),
                            Consumer.from(props.getGroup(), consumerName),
                            props.getReclaimIdle().toMillis(), pm.getId());
                    for (StreamMessage<String, String> message : claimed) {
                        handle(c, message);
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("reclaim skipped: {}", e.getMessage());
        }
    }

    private void deadLetter(RedisCommands<String, String> c, String id) {
        try {
            List<StreamMessage<String, String>> msgs = c.xrange(props.getStream(), Range.create(id, id));
            if (!msgs.isEmpty()) {
                Map<String, String> body = new HashMap<>(msgs.get(0).getBody());
                body.put(FIELD_DLQ_REASON, "max-deliveries-exceeded");
                c.xadd(props.getDlqStream(), body);
            }
            c.xack(props.getStream(), props.getGroup(), id);
            LOG.warn("moved poison job {} to DLQ {}", id, props.getDlqStream());
        } catch (Exception e) {
            LOG.debug("dead-letter of {} skipped: {}", id, e.getMessage());
        }
    }

    private void handle(RedisCommands<String, String> c, StreamMessage<String, String> message) {
        Map<String, String> body = message.getBody();
        String jobId = body.get(JobQueue.FIELD_JOB_ID);
        long start = System.nanoTime();
        try {
            if (jobId == null) {
                c.xack(props.getStream(), props.getGroup(), message.getId());
                return;
            }
            String reference = body.getOrDefault(JobQueue.FIELD_REFERENCE, "");
            if (props.getFailOnReference() != null && props.getFailOnReference().equals(reference)) {
                // Test hook: simulate a job that always fails, so it exhausts deliveries -> DLQ.
                throw new IllegalStateException("simulated poison job: " + reference);
            }
            simulateProcessing();
            long amount = parseLong(body.get(JobQueue.FIELD_AMOUNT));
            JobResult result = new JobResult(
                    jobId,
                    body.getOrDefault(JobQueue.FIELD_REFERENCE, ""),
                    amount,
                    amount * 2 / 100, // 2% fee
                    "PROCESSED",
                    Thread.currentThread().getName(),
                    System.currentTimeMillis());
            queue.release(result);
            c.xack(props.getStream(), props.getGroup(), message.getId());
            if (metrics != null) {
                metrics.recordProcessing(Duration.ofNanos(System.nanoTime() - start));
            }
        } catch (Exception e) {
            // Leave un-acked: it stays in the PEL and gets reclaimed/retried (or DLQ'd) later.
            LOG.warn("processing failed for job {}: {}", jobId, e.getMessage());
        }
    }

    private void simulateProcessing() {
        long min = props.getProcessLatencyMinMs();
        long max = Math.max(min, props.getProcessLatencyMaxMs());
        sleep(min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1));
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Duration.ofMillis(ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        for (Thread t : threads) {
            if (t != null) {
                t.interrupt();
            }
        }
    }
}
