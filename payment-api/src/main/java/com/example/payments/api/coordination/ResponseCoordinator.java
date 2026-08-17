package com.example.payments.api.coordination;

import com.example.payments.api.config.ApiProperties;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.model.SimulationStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Correlates the asynchronous Kafka response back to the blocked HTTP request.
 *
 * <p>Each in-flight request registers a {@link CompletableFuture}. When a final event
 * arrives (on any instance), that instance writes the result to Redis and publishes
 * the requestId on a Redis pub/sub channel; every instance is subscribed, so whichever
 * holds the waiting future completes it — correlation works across scaled instances.
 *
 * <p>The subscription is established tolerantly (retried if Redis is down at startup),
 * and pending waiters are released on shutdown so connections aren't left hanging.
 */
@Singleton
public class ResponseCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseCoordinator.class);

    /** OBS-01: maximum gap between store re-polls while a waiter's future has not completed. */
    private static final Duration REPOLL_INTERVAL = Duration.ofMillis(500);

    private final ConcurrentHashMap<String, CompletableFuture<StatusEntry>> waiters =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redis-subscribe-retry");
                t.setDaemon(true);
                return t;
            });
    // The PubSub listener below must never call complete() inline: complete() runs a
    // synchronous Redis command (RedisStatusStore.get()), and Lettuce dispatches every message
    // on the connection's single Netty event-loop thread — a slow command there stalls delivery
    // of every subsequent message for every other waiter on this instance, not just the current
    // one (task_3801253b). One virtual thread per message keeps that dispatch thread free.
    private final ExecutorService notificationDispatcher = Executors.newVirtualThreadPerTaskExecutor();

    private final RedisClient redisClient;
    private final RedisStatusStore store;
    private final ApiProperties properties;
    private volatile StatefulRedisPubSubConnection<String, String> pubSub;
    private volatile boolean shuttingDown;

    public ResponseCoordinator(RedisClient redisClient,
                               RedisStatusStore store,
                               ApiProperties properties) {
        this.redisClient = redisClient;
        this.store = store;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        trySubscribe();
    }

    private void trySubscribe() {
        if (shuttingDown) {
            return;
        }
        // The field is only assigned once subscribe() has actually succeeded; a connection that
        // opens but fails to subscribe is closed in the catch below instead of being leaked as
        // an orphaned Lettuce connection on every retry (AUD-17).
        StatefulRedisPubSubConnection<String, String> connection = null;
        try {
            connection = redisClient.connectPubSub();
            connection.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String requestId) {
                    onMessage(requestId);
                }
            });
            // Lettuce re-subscribes channels automatically after a reconnect.
            connection.sync().subscribe(properties.getResponseChannel());
            pubSub = connection;
            LOG.info("Subscribed to Redis channel {}", properties.getResponseChannel());
        } catch (Exception e) {
            if (connection != null) {
                connection.close();
            }
            LOG.warn("Redis pub/sub subscribe failed; retrying in 5s ({})", e.getMessage());
            scheduler.schedule(this::trySubscribe, 5, TimeUnit.SECONDS);
        }
    }

    /**
     * Registers a waiter for the given request. Idempotent.
     *
     * <p>Once shutdown has started the waiter is returned already released and is never put in
     * the registry: a late request must not park for its full budget against an API that is
     * going away, nor leave a registration nobody will drain (PAY-10).
     */
    public CompletableFuture<StatusEntry> register(String requestId) {
        if (shuttingDown) {
            return CompletableFuture.failedFuture(new IllegalStateException("API shutting down"));
        }
        return waiters.computeIfAbsent(requestId, k -> new CompletableFuture<>());
    }

    public void unregister(String requestId) {
        waiters.remove(requestId);
    }

    /** Number of requests currently blocked waiting for an async result. */
    public int pendingCount() {
        return waiters.size();
    }

    /**
     * Entry point for the Redis PubSub listener. Dispatches to {@link #complete} on a separate
     * thread — see {@link #notificationDispatcher}'s field comment for why this must never run
     * inline on the calling (Netty event-loop) thread. Package-private so a test can invoke it
     * directly without a real Lettuce PubSub connection.
     */
    void onMessage(String requestId) {
        notificationDispatcher.execute(() -> complete(requestId));
    }

    /** Completes a waiting future if the Redis entry has reached a terminal state. */
    public void complete(String requestId) {
        CompletableFuture<StatusEntry> future = waiters.get(requestId);
        if (future == null || future.isDone()) {
            return;
        }
        Optional<StatusEntry> entry = store.get(requestId);
        if (entry.isPresent() && isTerminal(entry.get().status())) {
            future.complete(entry.get());
        }
    }

    /** Same as {@link #complete} — used right after register to catch already-finished work. */
    public void completeFromStore(String requestId) {
        complete(requestId);
    }

    /**
     * Blocks (on the calling virtual thread) up to {@code timeout} for the result.
     *
     * <p>OBS-01: the Redis pub/sub notification is at-most-once - a message can be lost (a
     * subscribe race on reconnect, a dropped connection) without the underlying result ever
     * being missing from the store. Waiting on the future alone would then time out into a false
     * 202 even though the terminal result has been sitting in the store the whole time. Instead
     * of one {@code future.get(timeout)}, the wait is chunked into polls of at most {@link
     * #REPOLL_INTERVAL}: each timed-out chunk re-checks the store via {@link #completeFromStore}
     * (which completes the future if a terminal result is there) before the next chunk, so a
     * lost wake-up is caught well within the overall budget instead of only at its edge.
     *
     * @return the terminal entry, or empty on timeout/shutdown.
     */
    public Optional<StatusEntry> await(String requestId, CompletableFuture<StatusEntry> future) {
        long deadlineNanos = System.nanoTime() + properties.getWaitTimeout().toNanos();
        try {
            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return Optional.empty();
                }
                long chunkMillis = Math.min(REPOLL_INTERVAL.toMillis(),
                        Math.max(1, Duration.ofNanos(remainingNanos).toMillis()));
                try {
                    return Optional.of(future.get(chunkMillis, TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    completeFromStore(requestId);
                    if (future.isDone()) {
                        return Optional.of(future.get());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.debug("Await ended without result for {}: {}", requestId, e.getMessage());
            return Optional.empty();
        } finally {
            unregister(requestId);
        }
    }

    @PreDestroy
    void close() {
        shuttingDown = true;
        // Release blocked requests so they return 202 instead of hanging the connection, and drop
        // the registrations: shutdown is a termination path like any other (PAY-10).
        waiters.values().forEach(f ->
                f.completeExceptionally(new IllegalStateException("API shutting down")));
        waiters.clear();
        scheduler.shutdownNow();
        notificationDispatcher.shutdownNow();
        if (pubSub != null) {
            pubSub.close();
        }
    }

    private static boolean isTerminal(SimulationStatus status) {
        return status == SimulationStatus.COMPLETED || status == SimulationStatus.FAILED;
    }
}
