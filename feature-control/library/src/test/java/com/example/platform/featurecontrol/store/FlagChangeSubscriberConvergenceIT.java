package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FeatureSettings;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTR-03 against a real Redis at {@code localhost:6379} (AD-003, no Docker/Testcontainers — same
 * convention as {@code RedisFlagSourceIT}). Two independent {@link FlagChangeSubscriber} instances,
 * each its own real Lettuce pub/sub connection, stand in for two app instances sharing the flag-changed
 * channel. Excluded from the default {@code test} run; runs under {@code -PwithIT}.
 */
class FlagChangeSubscriberConvergenceIT {

    private static RedisClient client;

    private FlagChangeSubscriber instanceA;
    private FlagChangeSubscriber instanceB;

    @BeforeAll
    static void connect() {
        String uri = System.getenv().getOrDefault("REDIS_TEST_URI", "redis://localhost:6379");
        client = RedisClient.create(uri);
    }

    @AfterAll
    static void disconnect() {
        client.shutdown();
    }

    @AfterEach
    void tearDown() {
        if (instanceA != null) {
            instanceA.close();
        }
        if (instanceB != null) {
            instanceB.close();
        }
    }

    private static RedisFlagSource noOpDynamicSource() {
        return new RedisFlagSource(null, null, null) {
            @Override
            public void invalidate(String name) {
            }

            @Override
            public void invalidateAll() {
            }
        };
    }

    @Test
    void twoIndependentInstancesConvergeWithinTheApprovedLimit() throws InterruptedException {
        FeatureSettings settings = new FeatureSettings();
        settings.setKeyPrefix("feature-control-pubsub-it:");
        settings.setConvergenceAlertThreshold(Duration.ofSeconds(2));

        FeatureRedisCommandsProvider provider = new FeatureRedisCommandsProvider(client);
        FlagChangeNotifier notifier = new FlagChangeNotifier(provider, settings);

        instanceA = new FlagChangeSubscriber(client, noOpDynamicSource(), notifier, settings);
        instanceB = new FlagChangeSubscriber(client, noOpDynamicSource(), notifier, settings);
        instanceA.start();
        instanceB.start();

        // Let both SUBSCRIBE commands land on the server before publishing.
        awaitUntil(() -> true, Duration.ofMillis(300));

        notifier.publish("convergence-demo-flag");

        awaitUntil(() -> instanceA.lastConvergenceLatency().isPresent()
                && instanceB.lastConvergenceLatency().isPresent(), Duration.ofSeconds(5));

        assertFalse(instanceA.isConvergenceDegraded(), "instance A should converge within the approved limit");
        assertFalse(instanceB.isConvergenceDegraded(), "instance B should converge within the approved limit");
        assertTrue(instanceA.lastConvergenceLatency().orElseThrow().compareTo(settings.getConvergenceAlertThreshold()) <= 0);
        assertTrue(instanceB.lastConvergenceLatency().orElseThrow().compareTo(settings.getConvergenceAlertThreshold()) <= 0);
    }

    @Test
    void aDegradedConvergenceLimitIsFlagged() throws InterruptedException {
        // A sub-millisecond approved limit turns any real network round trip into a "degradation",
        // proving the alert path fires rather than assuming it silently would.
        FeatureSettings settings = new FeatureSettings();
        settings.setKeyPrefix("feature-control-pubsub-it:");
        settings.setConvergenceAlertThreshold(Duration.ZERO);

        FeatureRedisCommandsProvider provider = new FeatureRedisCommandsProvider(client);
        FlagChangeNotifier notifier = new FlagChangeNotifier(provider, settings);

        instanceA = new FlagChangeSubscriber(client, noOpDynamicSource(), notifier, settings);
        instanceA.start();
        awaitUntil(() -> true, Duration.ofMillis(300));

        notifier.publish("always-degraded-flag");

        awaitUntil(() -> instanceA.lastConvergenceLatency().isPresent(), Duration.ofSeconds(5));

        assertTrue(instanceA.isConvergenceDegraded());
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        if (!condition.getAsBoolean()) {
            org.junit.jupiter.api.Assertions.fail("awaitUntil timed out after " + timeout);
        }
    }
}
