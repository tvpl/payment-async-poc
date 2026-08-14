package com.example.platform.featurecontrol.resolver;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import com.example.platform.featurecontrol.source.FlagKeyReader;
import com.example.platform.featurecontrol.source.StaleFallback;
import com.example.platform.featurecontrol.store.CompositeFlagSource;
import com.example.platform.featurecontrol.store.RedisFlagSource;
import com.example.platform.featurecontrol.store.StaticFlagSource;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-02 against a real Redis at {@code localhost:6379} (same convention as {@code RedisFlagSourceIT}
 * / {@code VersionedFlagStoreIT} — no Docker/Testcontainers). Excluded from the default {@code test}
 * run (name ends in {@code IT}); runs under {@code -PwithIT}.
 *
 * <p>Proves the kill-switch latch: once armed by a fresh successful read, a Redis outage (or a
 * stale-policy fallback, in either configured mode) must never report "not killed" — only a
 * genuinely fresh read finding the flag absent (Redis healthy, no such key) may disarm it.
 */
class MasterSwitchIT {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static ObjectMapper objectMapper;

    private FeatureSettings settings;
    private FailableFlagKeyReader reader;
    private MasterSwitch masterSwitch;
    private String keyPrefix;

    @BeforeAll
    static void connect() {
        String uri = System.getenv().getOrDefault("REDIS_TEST_URI", "redis://localhost:6379");
        client = RedisClient.create(uri);
        connection = client.connect();
        objectMapper = ObjectMapper.getDefault();
    }

    @AfterAll
    static void disconnect() {
        connection.close();
        client.shutdown();
    }

    @BeforeEach
    void setUp() {
        keyPrefix = "feature-control-masterswitch-it-" + System.nanoTime() + ":";
        settings = new FeatureSettings();
        settings.setKeyPrefix(keyPrefix);
        settings.setCacheTtl(Duration.ofMillis(50));
        settings.setCacheTtlJitter(0.0); // deterministic timing for the assertions below
        settings.setMaxStale(Duration.ofMillis(200));
        reader = new FailableFlagKeyReader(connection);
        RedisFlagSource dynamic = new RedisFlagSource(reader, objectMapper, settings);
        CompositeFlagSource composite = new CompositeFlagSource(new StaticFlagSource(List.of()), dynamic);
        masterSwitch = new MasterSwitch(settings, composite);
    }

    @AfterEach
    void tearDown() {
        connection.sync().del(keyPrefix + MasterSwitch.KILL_FLAG);
    }

    private void seedKillSwitch(boolean enabled) {
        FlagDefinition def = new FlagDefinition(
                MasterSwitch.KILL_FLAG, FlagType.BOOLEAN, enabled, 0, null, null, null, "on", "off");
        try {
            connection.sync().set(keyPrefix + MasterSwitch.KILL_FLAG, objectMapper.writeValueAsString(def));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void armedLatchSurvivesAnOutageWithinMaxStaleUnderBaselineFallback() throws InterruptedException {
        settings.setStaleFallback(StaleFallback.BASELINE);
        seedKillSwitch(true);
        assertTrue(masterSwitch.isKilled(), "sanity: a fresh armed read must report killed");

        Thread.sleep(80); // past cache-ttl (50ms), still within max-stale (200ms)
        reader.setFailing(true);

        assertTrue(masterSwitch.isKilled(), "an outage within max-stale must not disarm the kill switch");
    }

    @Test
    void armedLatchSurvivesAnOutageBeyondMaxStaleUnderBaselineFallback() throws InterruptedException {
        // BASELINE beyond max-stale makes RedisFlagSource.find() defer to the (empty) YAML baseline —
        // the exact case that used to disarm the kill switch (AUD-02).
        settings.setStaleFallback(StaleFallback.BASELINE);
        seedKillSwitch(true);
        assertTrue(masterSwitch.isKilled(), "sanity: a fresh armed read must report killed");

        Thread.sleep(260); // past both cache-ttl (50ms) and max-stale (200ms)
        reader.setFailing(true);

        assertTrue(masterSwitch.isKilled(),
                "an outage beyond max-stale under BASELINE fallback must keep the latch armed");
    }

    @Test
    void armedLatchSurvivesAnOutageBeyondMaxStaleUnderFailClosedFallback() throws InterruptedException {
        // FAIL_CLOSED beyond max-stale makes RedisFlagSource.find() serve a synthetic forced-off
        // definition (enabled=false) — the exact case that used to disarm the kill switch (AUD-02):
        // the definition itself says "off" even though the switch was armed a moment ago.
        settings.setStaleFallback(StaleFallback.FAIL_CLOSED);
        seedKillSwitch(true);
        assertTrue(masterSwitch.isKilled(), "sanity: a fresh armed read must report killed");

        Thread.sleep(260);
        reader.setFailing(true);

        assertTrue(masterSwitch.isKilled(),
                "an outage beyond max-stale under FAIL_CLOSED fallback must keep the latch armed");
    }

    @Test
    void genuineRemovalWithRedisHealthyDisarmsTheLatch() throws InterruptedException {
        settings.setStaleFallback(StaleFallback.BASELINE);
        seedKillSwitch(true);
        assertTrue(masterSwitch.isKilled(), "sanity: a fresh armed read must report killed");

        connection.sync().del(keyPrefix + MasterSwitch.KILL_FLAG); // legitimate removal, Redis stays up
        Thread.sleep(80); // past cache-ttl so the next read is fresh, not cached

        assertFalse(masterSwitch.isKilled(),
                "a fresh read finding the flag genuinely absent (Redis healthy) must disarm the latch");
    }

    @Test
    void coldStartWithNoPriorReadStartsDisarmed() {
        reader.setFailing(true); // Redis has never answered for this key in this process

        assertFalse(masterSwitch.isKilled(),
                "cold start with no prior successful read must start disarmed (documented limitation)");
    }

    /** Real Redis I/O for the success path, with a deterministic on/off failure switch. */
    private static final class FailableFlagKeyReader implements FlagKeyReader {
        private final StatefulRedisConnection<String, String> connection;
        private final AtomicBoolean failing = new AtomicBoolean(false);

        FailableFlagKeyReader(StatefulRedisConnection<String, String> connection) {
            this.connection = connection;
        }

        @Override
        public String get(String key) {
            if (failing.get()) {
                throw new RuntimeException("simulated Redis outage");
            }
            return connection.sync().get(key);
        }

        void setFailing(boolean value) {
            failing.set(value);
        }
    }
}
