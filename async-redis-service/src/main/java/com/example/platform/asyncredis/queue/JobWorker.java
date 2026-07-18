package com.example.platform.asyncredis.queue;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.metrics.AsyncMetrics;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
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
 * blocking {@code XREADGROUP} loop on its own dedicated connection. Because the group tracks a Pending
 * Entries List, a crashed worker's in-flight jobs are not lost — {@link #reclaim} periodically inspects
 * pending entries and either re-claims those idle beyond {@code reclaim-idle} (so another worker
 * finishes them) or, once a job has been delivered more than {@code max-deliveries} times, moves it to
 * the dead-letter stream (poison protection, so a bad job can't loop forever). This is the durability
 * the plain pub/sub variant lacks.
 */
@Context
public class JobWorker {

    private static final Logger LOG = LoggerFactory.getLogger(JobWorker.class);
    private static final String FIELD_DLQ_REASON = "dlqReason";

    private final RedisConnections redis;
    private final JobQueue queue;
    private final AsyncRedisProperties props;
    @Nullable
    private final AsyncMetrics metrics;

    private final Thread[] threads;
    private volatile boolean running = true;

    public JobWorker(RedisConnections redis, JobQueue queue, AsyncRedisProperties props,
                     @Nullable AsyncMetrics metrics) {
        this.redis = redis;
        this.queue = queue;
        this.props = props;
        this.metrics = metrics;
        this.threads = new Thread[Math.max(1, props.getWorkerConcurrency())];
    }

    @PostConstruct
    void start() {
        ensureGroup();
        for (int i = 0; i < threads.length; i++) {
            String consumerName = "worker-" + i;
            Thread t = new Thread(() -> runLoop(consumerName), "async-worker-" + i);
            t.setDaemon(true);
            threads[i] = t;
            t.start();
        }
        LOG.info("Started {} async worker(s) on stream {}", threads.length, props.getStream());
    }

    private void ensureGroup() {
        try {
            redis.shared().xgroupCreate(
                    XReadArgs.StreamOffset.from(props.getStream(), "0-0"),
                    props.getGroup(),
                    XGroupCreateArgs.Builder.mkstream());
        } catch (Exception e) {
            // BUSYGROUP: group already exists — expected on restart.
            LOG.debug("Consumer group ensure: {}", e.getMessage());
        }
    }

    private void runLoop(String consumerName) {
        long blockMs = Math.min(2000, Math.max(100, props.getReclaimInterval().toMillis()));
        long lastReclaim = 0;
        try (StatefulRedisConnection<String, String> conn = redis.dedicated()) {
            RedisCommands<String, String> c = conn.sync();
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - lastReclaim >= props.getReclaimInterval().toMillis()) {
                        lastReclaim = now;
                        reclaim(c, consumerName);
                    }
                    List<StreamMessage<String, String>> messages = c.xreadgroup(
                            Consumer.from(props.getGroup(), consumerName),
                            XReadArgs.Builder.block(blockMs).count(16),
                            XReadArgs.StreamOffset.lastConsumed(props.getStream()));
                    if (messages != null) {
                        for (StreamMessage<String, String> message : messages) {
                            handle(c, message);
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        LOG.warn("worker {} loop error: {}", consumerName, e.getMessage());
                        sleep(500);
                    }
                }
            }
        }
        LOG.info("worker {} stopped", consumerName);
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
