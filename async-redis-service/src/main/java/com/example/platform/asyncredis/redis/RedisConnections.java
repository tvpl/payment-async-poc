package com.example.platform.asyncredis.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

/**
 * Redis connection helper for the async flow. Exposes two things:
 *
 * <ul>
 *   <li>a <strong>shared</strong> lazy sync connection for fast, non-blocking ops (XADD, SET, GET) —
 *       these never hold the socket, so one connection is enough;</li>
 *   <li>{@link #dedicated()} for <strong>blocking</strong> commands (BRPOP in the API, XREADGROUP in
 *       the worker). A blocking command monopolizes its connection until it returns, so each blocker
 *       gets its own connection which it closes when done.</li>
 * </ul>
 *
 * <p>Production note: opening a connection per blocking wait is simple and correct but not free under
 * high concurrency — a pooled variant ({@code ConnectionPoolSupport}) is the usual next step.
 */
@Singleton
public class RedisConnections {

    private final RedisClient client;
    private volatile StatefulRedisConnection<String, String> shared;

    public RedisConnections(RedisClient client) {
        this.client = client;
    }

    /** Shared connection for non-blocking commands. */
    public RedisCommands<String, String> shared() {
        StatefulRedisConnection<String, String> conn = shared;
        if (conn == null || !conn.isOpen()) {
            synchronized (this) {
                if (shared == null || !shared.isOpen()) {
                    shared = client.connect();
                }
            }
        }
        return shared.sync();
    }

    /** A fresh connection dedicated to one blocking command; the caller must close it. */
    public StatefulRedisConnection<String, String> dedicated() {
        return client.connect();
    }

    @PreDestroy
    void close() {
        if (shared != null) {
            shared.close();
        }
    }
}
