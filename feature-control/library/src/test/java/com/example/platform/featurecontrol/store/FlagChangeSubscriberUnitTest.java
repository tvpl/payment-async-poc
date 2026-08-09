package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.pubsub.PubSubConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTR-03, unit-level: reconnect-without-leaking, message handling and convergence tracking, driven
 * by a hand-written {@link PubSubConnector} fake instead of a real Redis connection — the specific
 * "connected but subscribe fails" scenario is exception-handling logic, not I/O, and is effectively
 * unreachable against a real, healthy local Redis.
 */
class FlagChangeSubscriberUnitTest {

    private final FeatureSettings settings = new FeatureSettings();
    private FlagChangeSubscriber subscriber;

    {
        settings.setKeyPrefix("test:");
        settings.setPubsubReconnectBaseDelay(Duration.ofMillis(1));
        settings.setPubsubReconnectMaxDelay(Duration.ofMillis(10));
    }

    @AfterEach
    void tearDown() {
        if (subscriber != null) {
            subscriber.close();
        }
    }

    private static RedisFlagSource trackingDynamicSource(Set<String> invalidated, AtomicBoolean invalidatedAll) {
        return new RedisFlagSource(null, null, null) {
            @Override
            public void invalidate(String name) {
                invalidated.add(name);
            }

            @Override
            public void invalidateAll() {
                invalidatedAll.set(true);
            }
        };
    }

    @Test
    void subscribeFailureAfterConnectClosesThePartialConnectionInsteadOfLeakingIt() throws InterruptedException {
        FakePubSubConnector connector = new FakePubSubConnector();
        connector.enqueueSubscribeOutcome(false); // first attempt: connect() succeeds, subscribe() throws
        connector.enqueueSubscribeOutcome(true);  // retry: succeeds

        FlagChangeNotifier notifier = new FlagChangeNotifier(null, settings);
        subscriber = new FlagChangeSubscriber(connector, trackingDynamicSource(new CopyOnWriteArraySet<>(), new AtomicBoolean()),
                notifier, settings);
        subscriber.start();

        awaitUntil(() -> connector.connections.size() >= 2, Duration.ofSeconds(2));

        assertEquals(2, connector.connections.size(), "one failed attempt + one successful retry");
        assertTrue(connector.connections.get(0).closed.get(),
                "the first (failed-at-subscribe) connection must be closed, not orphaned");
    }

    @Test
    void aReceivedMessageInvalidatesTheMatchingCacheEntry() {
        FakePubSubConnector connector = new FakePubSubConnector();
        connector.enqueueSubscribeOutcome(true);
        Set<String> invalidated = new CopyOnWriteArraySet<>();
        FlagChangeNotifier notifier = new FlagChangeNotifier(null, settings);
        subscriber = new FlagChangeSubscriber(connector, trackingDynamicSource(invalidated, new AtomicBoolean()),
                notifier, settings);
        subscriber.start();

        connector.connections.get(0).deliver("test:changed", "demo-toggle|" + System.currentTimeMillis());

        assertTrue(invalidated.contains("demo-toggle"));
    }

    @Test
    void aWildcardMessageInvalidatesEverything() {
        FakePubSubConnector connector = new FakePubSubConnector();
        connector.enqueueSubscribeOutcome(true);
        AtomicBoolean invalidatedAll = new AtomicBoolean();
        FlagChangeNotifier notifier = new FlagChangeNotifier(null, settings);
        subscriber = new FlagChangeSubscriber(connector, trackingDynamicSource(new CopyOnWriteArraySet<>(), invalidatedAll),
                notifier, settings);
        subscriber.start();

        connector.connections.get(0).deliver("test:changed", "*|" + System.currentTimeMillis());

        assertTrue(invalidatedAll.get());
    }

    @Test
    void convergenceLatencyIsRecordedFromTheEmbeddedPublishTimestamp() {
        FakePubSubConnector connector = new FakePubSubConnector();
        connector.enqueueSubscribeOutcome(true);
        FlagChangeNotifier notifier = new FlagChangeNotifier(null, settings);
        subscriber = new FlagChangeSubscriber(connector,
                trackingDynamicSource(new CopyOnWriteArraySet<>(), new AtomicBoolean()), notifier, settings);
        subscriber.start();

        long publishedAt = System.currentTimeMillis() - 10;
        connector.connections.get(0).deliver("test:changed", "f|" + publishedAt);

        Duration latency = subscriber.lastConvergenceLatency().orElseThrow();
        assertTrue(latency.toMillis() >= 0 && latency.toMillis() < 5_000, "unexpected latency: " + latency);
    }

    @Test
    void malformedPayloadStillInvalidatesButSkipsConvergenceTracking() {
        FakePubSubConnector connector = new FakePubSubConnector();
        connector.enqueueSubscribeOutcome(true);
        Set<String> invalidated = new CopyOnWriteArraySet<>();
        FlagChangeNotifier notifier = new FlagChangeNotifier(null, settings);
        subscriber = new FlagChangeSubscriber(connector, trackingDynamicSource(invalidated, new AtomicBoolean()),
                notifier, settings);
        subscriber.start();

        connector.connections.get(0).deliver("test:changed", "legacy-payload-without-timestamp");

        assertTrue(invalidated.contains("legacy-payload-without-timestamp"));
        assertTrue(subscriber.lastConvergenceLatency().isEmpty());
    }

    @Test
    void isConvergenceDegradedReflectsTheApprovedLimit() {
        settings.setConvergenceAlertThreshold(Duration.ofMillis(5));
        FakePubSubConnector connector = new FakePubSubConnector();
        connector.enqueueSubscribeOutcome(true);
        FlagChangeNotifier notifier = new FlagChangeNotifier(null, settings);
        subscriber = new FlagChangeSubscriber(connector,
                trackingDynamicSource(new CopyOnWriteArraySet<>(), new AtomicBoolean()), notifier, settings);
        subscriber.start();

        long longAgo = System.currentTimeMillis() - 5_000; // far beyond the 5ms approved limit
        connector.connections.get(0).deliver("test:changed", "f|" + longAgo);

        assertTrue(subscriber.isConvergenceDegraded());
    }

    @Test
    void closeClosesTheActiveConnection() {
        FakePubSubConnector connector = new FakePubSubConnector();
        connector.enqueueSubscribeOutcome(true);
        FlagChangeNotifier notifier = new FlagChangeNotifier(null, settings);
        subscriber = new FlagChangeSubscriber(connector,
                trackingDynamicSource(new CopyOnWriteArraySet<>(), new AtomicBoolean()), notifier, settings);
        subscriber.start();

        subscriber.close();

        assertTrue(connector.connections.get(0).closed.get());
        subscriber = null; // already closed; avoid a redundant close() in tearDown
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        org.junit.jupiter.api.Assertions.fail("awaitUntil timed out after " + timeout);
    }

    /** Hand-written {@link PubSubConnector} fake — no real Redis I/O, deterministic fault injection. */
    private static final class FakePubSubConnector implements PubSubConnector {
        private final Deque<Boolean> subscribeOutcomes = new ArrayDeque<>();
        private final List<FakeConnection> connections = new ArrayList<>();

        void enqueueSubscribeOutcome(boolean succeeds) {
            subscribeOutcomes.addLast(succeeds);
        }

        @Override
        public Connection connect() {
            FakeConnection connection = new FakeConnection(!subscribeOutcomes.isEmpty() && subscribeOutcomes.pollFirst());
            connections.add(connection);
            return connection;
        }

        private static final class FakeConnection implements Connection {
            private final boolean subscribeSucceeds;
            private final AtomicBoolean closed = new AtomicBoolean(false);
            private final AtomicInteger closeCount = new AtomicInteger();
            private volatile BiConsumer<String, String> listener;

            FakeConnection(boolean subscribeSucceeds) {
                this.subscribeSucceeds = subscribeSucceeds;
            }

            @Override
            public void addListener(BiConsumer<String, String> onMessage) {
                this.listener = onMessage;
            }

            @Override
            public void subscribe(String channel) {
                if (!subscribeSucceeds) {
                    throw new RuntimeException("simulated subscribe failure");
                }
            }

            @Override
            public boolean isOpen() {
                return !closed.get();
            }

            @Override
            public void close() {
                closed.set(true);
                closeCount.incrementAndGet();
            }

            void deliver(String channel, String payload) {
                listener.accept(channel, payload);
            }
        }
    }
}
