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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Correlates the asynchronous Kafka response back to the blocked HTTP request.
 *
 * <p>Each in-flight request registers a {@link CompletableFuture}. When a final
 * event arrives (on any instance), that instance writes the result to Redis and
 * publishes the requestId on a Redis pub/sub channel. <em>Every</em> API instance
 * is subscribed, so whichever one holds the waiting future completes it — this is
 * what makes correlation work across horizontally scaled instances.
 */
@Singleton
public class ResponseCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseCoordinator.class);

    private final ConcurrentHashMap<String, CompletableFuture<StatusEntry>> waiters =
            new ConcurrentHashMap<>();

    private final RedisClient redisClient;
    private final RedisStatusStore store;
    private final ApiProperties properties;
    private StatefulRedisPubSubConnection<String, String> pubSub;

    public ResponseCoordinator(RedisClient redisClient,
                               RedisStatusStore store,
                               ApiProperties properties) {
        this.redisClient = redisClient;
        this.store = store;
        this.properties = properties;
    }

    @PostConstruct
    void subscribe() {
        pubSub = redisClient.connectPubSub();
        pubSub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String requestId) {
                complete(requestId);
            }
        });
        pubSub.sync().subscribe(properties.getResponseChannel());
        LOG.info("Subscribed to Redis channel {}", properties.getResponseChannel());
    }

    @PreDestroy
    void close() {
        if (pubSub != null) {
            pubSub.close();
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

    /**
     * Blocks (on the calling virtual thread) up to {@code timeout} for the result.
     *
     * @return the terminal entry, or empty on timeout.
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
            LOG.error("Error awaiting result for {}", requestId, e);
            return Optional.empty();
        } finally {
            unregister(requestId);
        }
    }

    private static boolean isTerminal(SimulationStatus status) {
        return status == SimulationStatus.COMPLETED || status == SimulationStatus.FAILED;
    }
}
