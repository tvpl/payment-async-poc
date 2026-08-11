package com.example.platform.asyncredis.redis;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.support.ConnectionPoolSupport;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Redis connection helper for the async flow. Exposes:
 *
 * <ul>
 *   <li>a <strong>shared</strong> lazy sync connection for fast, non-blocking ops (XADD, SET, GET) —
 *       these never hold the socket, so one connection is enough;</li>
 *   <li>{@link #acquireWait(long)} for the API's <strong>blocking</strong> BRPOP, backed by a bounded
 *       <em>connection pool</em>. A blocking command monopolizes its connection until it returns, so
 *       each waiter needs its own; pooling bounds and reuses them instead of opening one per request
 *       (the earlier connection-per-wait approach).</li>
 * </ul>
 *
 * <p>The worker's long-lived XREADGROUP connections are managed separately by the worker (bounded by
 * {@code worker-concurrency}), so they don't compete with the request pool.
 */
@Singleton
public class RedisConnections {

    private static final Logger LOG = LoggerFactory.getLogger(RedisConnections.class);

    private final RedisClient client;
    private final AsyncRedisProperties props;
    private volatile StatefulRedisConnection<String, String> shared;
    private volatile GenericObjectPool<StatefulRedisConnection<String, String>> pool;
    private volatile boolean policyApplied;

    public RedisConnections(RedisClient client, AsyncRedisProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * The client, with this service's connection policy applied once before first use.
     *
     * <p>Reconnection is this service's job, not the driver's (RED-05). Lettuce's default is to
     * reconnect transparently and hold dispatched commands in a buffer until it succeeds, which
     * hides an outage instead of surfacing it: a worker parked inside a blocking {@code XREADGROUP}
     * never learns its Redis is gone, so readiness keeps claiming capacity that does not exist and
     * the reconnect backoff never runs. Failing the command instead lets every caller here — worker
     * loop, wait lease, shared connection — react on its own terms.
     *
     * <p>Applied lazily rather than in the constructor so building this class stays free of side
     * effects on an injected bean.
     */
    private RedisClient client() {
        if (!policyApplied) {
            synchronized (this) {
                if (!policyApplied) {
                    client.setOptions(ClientOptions.builder()
                            .autoReconnect(false)
                            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                            .build());
                    policyApplied = true;
                }
            }
        }
        return client;
    }

    /** Shared connection for non-blocking commands. */
    public RedisCommands<String, String> shared() {
        StatefulRedisConnection<String, String> conn = shared;
        if (conn == null || !conn.isOpen()) {
            synchronized (this) {
                if (shared == null || !shared.isOpen()) {
                    shared = client().connect();
                }
            }
        }
        return shared.sync();
    }

    /**
     * Leases a pooled connection for one blocking command, waiting at most {@code maxWaitMillis} to
     * get one (RED-02). The caller passes what is left of its own HTTP budget, so acquisition can
     * never outlive the request paying for it. A pool that stays saturated for the whole window
     * fails with {@link java.util.NoSuchElementException} instead of parking the thread, which is
     * what an unbounded borrow does by default.
     *
     * <p>The lease — rather than {@code close()} on the connection — is what returns capacity to the
     * pool. Lettuce's {@code ConnectionPoolSupport} only wraps connections handed out by the no-arg
     * {@code borrowObject()}; the timed overloads this method needs return the <em>raw</em>
     * connection, whose {@code close()} shuts the socket down instead of recycling it. Borrowing with
     * a deadline and closing the result would therefore destroy one slot of pool capacity per wait
     * until the pool served nothing at all.
     */
    public WaitLease acquireWait(long maxWaitMillis) throws Exception {
        GenericObjectPool<StatefulRedisConnection<String, String>> p = pool();
        return new WaitLease(p, p.borrowObject(Duration.ofMillis(Math.max(0, maxWaitMillis))));
    }

    /** Connections currently checked out. Bounded by {@link #poolCapacity()} at all times. */
    public int borrowedConnections() {
        return pool().getNumActive();
    }

    /** The configured ceiling on concurrent blocking waits. */
    public int poolCapacity() {
        return pool().getMaxTotal();
    }

    /** A fresh, non-pooled connection for a worker's long-lived blocking loop; caller closes it. */
    public StatefulRedisConnection<String, String> dedicated() {
        return client().connect();
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
                    // Bounded acquisition backstop. Without an explicit maxWait the pool waits
                    // forever, so a saturated pool would hold request threads well past the HTTP
                    // budget instead of shedding them (RED-02).
                    cfg.setBlockWhenExhausted(true);
                    cfg.setMaxWait(props.getPoolMaxWait());
                    // With driver-level reconnection off, a connection that died while idle stays
                    // dead. Validating on borrow spends one isOpen() check to avoid handing a waiter
                    // a socket that can only fail.
                    cfg.setTestOnBorrow(true);
                    pool = ConnectionPoolSupport.createGenericObjectPool(() -> client().connect(), cfg);
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

    /**
     * One checked-out wait connection. Closing the lease returns the slot to the pool; a lease marked
     * {@link #invalidate() invalid} destroys the connection instead, so a socket left mid-protocol by
     * a failed blocking command is never handed to the next waiter.
     */
    public static final class WaitLease implements AutoCloseable {

        private final GenericObjectPool<StatefulRedisConnection<String, String>> pool;
        private final StatefulRedisConnection<String, String> connection;
        private boolean broken;

        private WaitLease(GenericObjectPool<StatefulRedisConnection<String, String>> pool,
                          StatefulRedisConnection<String, String> connection) {
            this.pool = pool;
            this.connection = connection;
        }

        public RedisCommands<String, String> sync() {
            return connection.sync();
        }

        /** Marks the connection unusable, so {@link #close()} destroys it rather than recycling it. */
        public void invalidate() {
            broken = true;
        }

        @Override
        public void close() {
            try {
                if (broken) {
                    pool.invalidateObject(connection);
                } else {
                    pool.returnObject(connection);
                }
            } catch (Exception e) {
                // Never let capacity bookkeeping fail the request that already got its answer.
                LOG.warn("could not release a wait connection: {}", e.getMessage());
            }
        }
    }
}
