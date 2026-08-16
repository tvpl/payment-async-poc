package com.example.platform.asyncredis.redis;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.push.PushListener;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.RedisCommand;
import io.lettuce.core.resource.ClientResources;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-19: the shared connection {@code RedisConnections.shared()} recreates after a dead socket is
 * detected must not silently drop the superseded one — it has to be closed explicitly. No mocking
 * framework or live Redis is a dependency of this module, so {@link RedisConnections#newSharedConnection()}
 * is the seam: a same-package test subclass overrides it to hand back controllable fakes, and this
 * test asserts on {@code close()} having actually been called on the connection it replaced.
 */
class RedisConnectionsUnitTest {

    /** Bare-minimum fake: only isOpen()/close() matter here: every command method is unreachable. */
    private static final class FakeConnection implements StatefulRedisConnection<String, String> {
        private volatile boolean open = true;
        private final AtomicInteger closeCalls = new AtomicInteger();

        boolean wasClosed() {
            return closeCalls.get() > 0;
        }

        /** Flips {@code isOpen()} to false without counting as a {@link #close()} call — models a
         * socket that died on its own (e.g. a reconnect event), which is the AUD-19 scenario:
         * {@code shared()} must detect this and close the connection explicitly itself. */
        void killSocketWithoutClosing() {
            open = false;
        }

        @Override
        public boolean isMulti() {
            return false;
        }

        @Override
        public RedisCommands<String, String> sync() {
            // shared() always calls sync() on its way out; this test only cares about isOpen()/
            // close(), so a null stand-in is enough (never dereferenced by these tests).
            return null;
        }

        @Override
        public RedisAsyncCommands<String, String> async() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RedisReactiveCommands<String, String> reactive() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addListener(PushListener listener) {
        }

        @Override
        public void removeListener(PushListener listener) {
        }

        @Override
        public void addListener(RedisConnectionStateListener listener) {
        }

        @Override
        public void removeListener(RedisConnectionStateListener listener) {
        }

        @Override
        public void setTimeout(Duration timeout) {
        }

        @Override
        public Duration getTimeout() {
            return Duration.ZERO;
        }

        @Override
        public <T> RedisCommand<String, String, T> dispatch(RedisCommand<String, String, T> command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<RedisCommand<String, String, ?>> dispatch(
                Collection<? extends RedisCommand<String, String, ?>> commands) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            open = false;
            closeCalls.incrementAndGet();
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            close();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public ClientOptions getOptions() {
            return null;
        }

        @Override
        public ClientResources getResources() {
            return null;
        }

        @Override
        public void reset() {
        }

        @Override
        public void setAutoFlushCommands(boolean autoFlush) {
        }

        @Override
        public void flushCommands() {
        }
    }

    /** Hands back a scripted sequence of connections instead of opening real sockets. */
    private static final class TestableRedisConnections extends RedisConnections {
        private final Deque<StatefulRedisConnection<String, String>> toHandOut = new ArrayDeque<>();

        TestableRedisConnections(AsyncRedisProperties props) {
            super(null, props);
        }

        void willReturn(StatefulRedisConnection<String, String> connection) {
            toHandOut.addLast(connection);
        }

        @Override
        StatefulRedisConnection<String, String> newSharedConnection() {
            StatefulRedisConnection<String, String> next = toHandOut.pollFirst();
            if (next == null) {
                throw new IllegalStateException("test did not script enough connections");
            }
            return next;
        }
    }

    @Test
    void recreatingTheSharedConnectionClosesThePreviousOne() {
        TestableRedisConnections connections = new TestableRedisConnections(new AsyncRedisProperties());
        FakeConnection first = new FakeConnection();
        FakeConnection second = new FakeConnection();
        connections.willReturn(first);
        connections.willReturn(second);

        connections.shared();
        assertFalse(first.wasClosed(), "the first connection must not be closed while still healthy");

        first.killSocketWithoutClosing();

        connections.shared();

        assertTrue(first.wasClosed(),
                "the superseded connection must be closed explicitly when shared() recreates it");
    }

    @Test
    void aHealthyConnectionIsReusedNotRecreated() {
        TestableRedisConnections connections = new TestableRedisConnections(new AsyncRedisProperties());
        FakeConnection first = new FakeConnection();
        FakeConnection second = new FakeConnection();
        connections.willReturn(first);
        connections.willReturn(second);

        connections.shared();
        connections.shared();
        connections.shared();

        assertFalse(first.wasClosed(), "a still-open connection must never be closed by shared()");
    }
}
