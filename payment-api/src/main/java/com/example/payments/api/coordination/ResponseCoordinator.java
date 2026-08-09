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

    private final ConcurrentHashMap<String, CompletableFuture<StatusEntry>> waiters =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redis-subscribe-retry");
                t.setDaemon(true);
                return t;
            });

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
        try {
            pubSub = redisClient.connectPubSub();
            pubSub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String requestId) {
                    complete(requestId);
                }
            });
            // Lettuce re-subscribes channels automatically after a reconnect.
            pubSub.sync().subscribe(properties.getResponseChannel());
            LOG.info("Subscribed to Redis channel {}", properties.getResponseChannel());
        } catch (Exception e) {
            LOG.warn("Redis pub/sub subscribe failed; retrying in 5s ({})", e.getMessage());
            scheduler.schedule(this::trySubscribe, 5, TimeUnit.SECONDS);
        }
    }

    /** Registers a waiter for the given request. Idempotent. */
    public CompletableFuture<StatusEntry> register(String requestId) {
        return waiters.computeIfAbsent(requestId, k -> new CompletableFuture<>());
    }

    public void unregister(String requestId) {
        waiters.remove(requestId);
    }

    /** Number of requests currently blocked waiting for an async result. */
    public int pendingCount() {
        return waiters.size();
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
     * @return the terminal entry, or empty on timeout/shutdown.
     */
    public Optional<StatusEntry> await(String requestId, CompletableFuture<StatusEntry> future) {
        Duration timeout = properties.getWaitTimeout();
        try {
            return Optional.of(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
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
        // Release blocked requests so they return 202 instead of hanging the connection.
        waiters.values().forEach(f ->
                f.completeExceptionally(new IllegalStateException("API shutting down")));
        scheduler.shutdownNow();
        if (pubSub != null) {
            pubSub.close();
        }
    }

    private static boolean isTerminal(SimulationStatus status) {
        return status == SimulationStatus.COMPLETED || status == SimulationStatus.FAILED;
    }
}
