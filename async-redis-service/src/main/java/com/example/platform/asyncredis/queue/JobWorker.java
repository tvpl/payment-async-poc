package com.example.platform.asyncredis.queue;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResult;
import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.platform.asyncredis.redis.RedisConnections;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Consumes jobs from the Redis Stream with a consumer group and releases results. Each worker runs a
 * blocking {@code XREADGROUP} loop on its own dedicated connection. Because the group tracks a Pending
 * Entries List, a crashed worker's in-flight jobs are not lost — {@link #reclaim} periodically
 * {@code XAUTOCLAIM}s messages idle beyond {@code reclaim-idle} so another worker finishes them. This
 * is the durability the plain pub/sub variant lacks.
 */
@Context
public class JobWorker {

    private static final Logger LOG = LoggerFactory.getLogger(JobWorker.class);

    private final RedisConnections redis;
    private final JobQueue queue;
    private final AsyncRedisProperties props;

    private final Thread[] threads;
    private volatile boolean running = true;

    public JobWorker(RedisConnections redis, JobQueue queue, AsyncRedisProperties props) {
        this.redis = redis;
        this.queue = queue;
        this.props = props;
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
        long blockMs = 2000;
        int sinceReclaim = 0;
        try (StatefulRedisConnection<String, String> conn = redis.dedicated()) {
            RedisCommands<String, String> c = conn.sync();
            while (running) {
                try {
                    if (sinceReclaim++ >= 10) {
                        sinceReclaim = 0;
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

    private void reclaim(RedisCommands<String, String> c, String consumerName) {
        try {
            ClaimedMessages<String, String> claimed = c.xautoclaim(props.getStream(),
                    XAutoClaimArgs.Builder.xautoclaim(
                            Consumer.from(props.getGroup(), consumerName),
                            props.getReclaimIdle(), "0-0"));
            if (claimed != null && claimed.getMessages() != null) {
                for (StreamMessage<String, String> message : claimed.getMessages()) {
                    handle(c, message);
                }
            }
        } catch (Exception e) {
            LOG.debug("reclaim skipped: {}", e.getMessage());
        }
    }

    private void handle(RedisCommands<String, String> c, StreamMessage<String, String> message) {
        Map<String, String> body = message.getBody();
        String jobId = body.get(JobQueue.FIELD_JOB_ID);
        try {
            if (jobId == null) {
                c.xack(props.getStream(), props.getGroup(), message.getId());
                return;
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
        } catch (Exception e) {
            // Leave un-acked: it stays in the PEL and gets reclaimed/retried later.
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
