package com.example.platform.asyncredis.redis;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.support.ConnectionPoolSupport;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

/**
 * Redis connection helper for the async flow. Exposes:
 *
 * <ul>
 *   <li>a <strong>shared</strong> lazy sync connection for fast, non-blocking ops (XADD, SET, GET) —
 *       these never hold the socket, so one connection is enough;</li>
 *   <li>{@link #borrowBlocking()} for the API's <strong>blocking</strong> BRPOP, backed by a bounded
 *       <em>connection pool</em>. A blocking command monopolizes its connection until it returns, so
 *       each waiter needs its own; pooling bounds and reuses them instead of opening one per request
 *       (the earlier connection-per-wait approach). The returned connection is returned to the pool
 *       on {@code close()} (use it in try-with-resources).</li>
 * </ul>
 *
 * <p>The worker's long-lived XREADGROUP connections are managed separately by the worker (bounded by
 * {@code worker-concurrency}), so they don't compete with the request pool.
 */
@Singleton
public class RedisConnections {

    private final RedisClient client;
    private final AsyncRedisProperties props;
    private volatile StatefulRedisConnection<String, String> shared;
    private volatile GenericObjectPool<StatefulRedisConnection<String, String>> pool;

    public RedisConnections(RedisClient client, AsyncRedisProperties props) {
        this.client = client;
        this.props = props;
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

    /** Borrows a pooled connection for one blocking command; close() returns it to the pool. */
    public StatefulRedisConnection<String, String> borrowBlocking() throws Exception {
        return pool().borrowObject();
    }

    /** A fresh, non-pooled connection for a worker's long-lived blocking loop; caller closes it. */
    public StatefulRedisConnection<String, String> dedicated() {
        return client.connect();
    }

    private GenericObjectPool<StatefulRedisConnection<String, String>> pool() {
        GenericObjectPool<StatefulRedisConnection<String, String>> p = pool;
        if (p == null) {
            synchronized (this) {
                if (pool == null) {
                    GenericObjectPoolConfig<StatefulRedisConnection<String, String>> cfg =
                            new GenericObjectPoolConfig<>();
                    cfg.setMaxTotal(props.getPoolMaxTotal());
                    cfg.setMaxIdle(props.getPoolMaxTotal());
                    cfg.setMinIdle(1);
                    pool = ConnectionPoolSupport.createGenericObjectPool(client::connect, cfg);
                }
                p = pool;
            }
        }
        return p;
    }

    @PreDestroy
    void close() {
        if (pool != null) {
            pool.close();
        }
        if (shared != null) {
            shared.close();
        }
    }
}
