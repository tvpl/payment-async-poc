package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import com.example.platform.featurecontrol.source.FlagKeyReader;
import com.example.platform.featurecontrol.source.StaleFallback;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTR-02 against a real Redis at {@code localhost:6379} (the sandbox root owns it per AD-003, no
 * Docker/Testcontainers — same convention as async-redis-service's ITs). Excluded from the default
 * {@code test} run (name ends in {@code IT}); runs under {@code -PwithIT}.
 */
class RedisFlagSourceIT {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static ObjectMapper objectMapper;

    private FeatureSettings settings;
    private CountingFlagKeyReader reader;
    private RedisFlagSource source;
    private String flagName;

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
        settings = new FeatureSettings();
        settings.setKeyPrefix("feature-control-it:");
        settings.setCacheTtl(Duration.ofMillis(50));
        settings.setCacheTtlJitter(0.0); // deterministic timing for the assertions below
        settings.setMaxStale(Duration.ofMillis(200));
        settings.setStaleFallback(StaleFallback.BASELINE);
        reader = new CountingFlagKeyReader(connection);
        source = new RedisFlagSource(reader, objectMapper, settings);
        flagName = "it-flag-" + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        connection.sync().del(settings.getKeyPrefix() + flagName);
    }

    private void seed(boolean enabled) {
        FlagDefinition def = new FlagDefinition(flagName, FlagType.BOOLEAN, enabled, 0,
                null, null, null, "on", "off");
        try {
            connection.sync().set(settings.getKeyPrefix() + flagName, objectMapper.writeValueAsString(def));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void freshFetchIsServedFromRedisAndThenFromCache() {
        seed(true);

        FlagDefinition first = source.find(flagName).orElseThrow();
        assertTrue(first.enabled());
        assertEquals(1, reader.callCount());

        // Within cache-ttl: served from cache, no second Redis round trip.
        source.find(flagName);
        assertEquals(1, reader.callCount(), "a cache hit must not re-query Redis");
    }

    @Test
    void ageOfIsObservableAndStartsNearZero() {
        seed(true);
        source.find(flagName);

        Duration age = source.ageOf(flagName).orElseThrow();
        assertTrue(age.toMillis() < 2_000, "age right after a successful fetch should be near zero: " + age);
    }

    @Test
    void ageOfIsEmptyWhenNeverFetched() {
        assertTrue(source.ageOf("never-queried-" + System.nanoTime()).isEmpty());
    }

    @Test
    void outageWithinMaxStaleServesLastKnownGood() throws InterruptedException {
        seed(true);
        FlagDefinition first = source.find(flagName).orElseThrow();

        Thread.sleep(80); // past cache-ttl (50ms), still well within max-stale (200ms)
        reader.setFailing(true);
        FlagDefinition duringOutage = source.find(flagName).orElseThrow();

        assertEquals(first.enabled(), duringOutage.enabled(), "LKG must be served unchanged within max-stale");
    }

    @Test
    void outageBeyondMaxStaleWithBaselinePolicyDefersToBaseline() throws InterruptedException {
        settings.setStaleFallback(StaleFallback.BASELINE);
        seed(true);
        source.find(flagName);

        Thread.sleep(260); // past both cache-ttl (50ms) and max-stale (200ms)
        reader.setFailing(true);
        var result = source.find(flagName);

        assertTrue(result.isEmpty(), "beyond max-stale with BASELINE must defer to the composite's YAML baseline");
    }

    @Test
    void outageBeyondMaxStaleWithFailClosedForcesOff() throws InterruptedException {
        settings.setStaleFallback(StaleFallback.FAIL_CLOSED);
        seed(true);
        FlagDefinition beforeOutage = source.find(flagName).orElseThrow();
        assertTrue(beforeOutage.enabled(), "sanity: the seeded flag starts enabled");

        Thread.sleep(260);
        reader.setFailing(true);
        FlagDefinition afterOutage = source.find(flagName).orElseThrow();

        assertFalse(afterOutage.enabled(), "beyond max-stale with FAIL_CLOSED must force the flag off");
    }

    @Test
    void neverFetchedOutageWithFailClosedStillForcesOff() {
        settings.setStaleFallback(StaleFallback.FAIL_CLOSED);
        reader.setFailing(true); // Redis has never answered for this key

        FlagDefinition result = source.find(flagName).orElseThrow();
        assertFalse(result.enabled());
    }

    @Test
    void neverFetchedOutageWithBaselinePolicyReturnsEmpty() {
        settings.setStaleFallback(StaleFallback.BASELINE);
        reader.setFailing(true);

        assertTrue(source.find(flagName).isEmpty());
    }

    @Test
    void concurrentMissesForTheSameKeyCollapseIntoASingleRedisCall() throws InterruptedException {
        seed(true);
        source.find(flagName); // warm cache once (1 call)
        try {
            Thread.sleep(70); // past cache-ttl -> next round of find() calls will all miss
        } catch (InterruptedException ignored) {
        }

        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        int callsBeforeStampede = reader.callCount();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    source.find(flagName);
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(callsBeforeStampede + 1, reader.callCount(),
                "20 concurrent misses for the same key must single-flight into exactly 1 Redis call");
    }

    /** Real Redis I/O for the success path, with a deterministic on/off failure switch and a call counter. */
    private static final class CountingFlagKeyReader implements FlagKeyReader {
        private final StatefulRedisConnection<String, String> connection;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean failing = new AtomicBoolean(false);

        CountingFlagKeyReader(StatefulRedisConnection<String, String> connection) {
            this.connection = connection;
        }

        @Override
        public String get(String key) {
            calls.incrementAndGet();
            if (failing.get()) {
                throw new RuntimeException("simulated Redis outage");
            }
            return connection.sync().get(key);
        }

        int callCount() {
            return calls.get();
        }

        void setFailing(boolean value) {
            failing.set(value);
        }
    }
}
