package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTR-04 against a real Redis at {@code localhost:6379} (AD-003, no Docker/Testcontainers — same
 * convention as {@code RedisFlagSourceIT}/{@code FlagChangeSubscriberConvergenceIT}). Excluded from
 * the default {@code test} run; runs under {@code -PwithIT}.
 */
class VersionedFlagStoreIT {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static ObjectMapper objectMapper;

    private FeatureSettings settings;
    private VersionedFlagStore store;
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
        settings.setKeyPrefix("feature-control-cas-it:");
        FeatureRedisCommandsProvider provider = new FeatureRedisCommandsProvider(client);
        store = new VersionedFlagStore(provider, settings, objectMapper);
        flagName = "cas-it-flag-" + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        connection.sync().del(store.flagKey(flagName));
        connection.sync().del(store.auditStreamKey());
    }

    private String json(FlagDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<StreamMessage<String, String>> auditEntries() {
        return connection.sync().xrange(store.auditStreamKey(), io.lettuce.core.Range.unbounded());
    }

    @Test
    void createSucceedsFromVersionZeroAndWritesAnAuditEntryAtomically() {
        FlagDefinition created = new FlagDefinition(flagName, FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 1, null);
        long result = store.put(flagName, 0, json(created), "alice");

        assertEquals(1L, result);
        List<StreamMessage<String, String>> entries = auditEntries();
        assertEquals(1, entries.size(), "exactly one audit entry for one accepted mutation");
    }

    @Test
    void auditEntryCapturesActorVersionResultAndBeforeAfter() {
        FlagDefinition created = new FlagDefinition(flagName, FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 1, null);
        String createdJson = json(created);
        store.put(flagName, 0, createdJson, "alice");

        Map<String, String> body = auditEntries().get(0).getBody();
        assertEquals("put", body.get("action"));
        assertEquals(flagName, body.get("flag"));
        assertEquals("alice", body.get("actor"));
        assertEquals("", body.get("before"), "no prior value existed yet");
        assertEquals(createdJson, body.get("after"));
        assertEquals("1", body.get("version"));
        assertEquals("ok", body.get("result"));
        assertTrue(Long.parseLong(body.get("ts")) > 0, "a real timestamp was recorded");
    }

    @Test
    void updateCapturesThePreviousValueAsBefore() {
        FlagDefinition v1 = new FlagDefinition(flagName, FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 1, null);
        String v1Json = json(v1);
        store.put(flagName, 0, v1Json, "alice");

        FlagDefinition v2 = new FlagDefinition(flagName, FlagType.BOOLEAN, false, 0, null, null, null, "on", "off", 2, null);
        String v2Json = json(v2);
        store.put(flagName, 1, v2Json, "bob");

        Map<String, String> secondEntry = auditEntries().get(1).getBody();
        assertEquals(v1Json, secondEntry.get("before"), "before must be the value that existed just before this write");
        assertEquals(v2Json, secondEntry.get("after"));
        assertEquals("bob", secondEntry.get("actor"));
    }

    @Test
    void staleCreateReturnsConflictAndWritesNoAuditEntry() {
        FlagDefinition v1 = new FlagDefinition(flagName, FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 1, null);
        store.put(flagName, 0, json(v1), "alice");

        // Someone else already created it (version now 1) — a second "version 0" writer is stale.
        FlagDefinition staleAttempt = new FlagDefinition(flagName, FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 1, null);
        long result = store.put(flagName, 0, json(staleAttempt), "mallory");

        assertEquals(-1L, result);
        assertEquals(1, auditEntries().size(), "the rejected write must not add a phantom audit entry");
    }

    @Test
    void deleteWithTheCorrectVersionSucceedsAndRemovesTheKey() {
        FlagDefinition v1 = new FlagDefinition(flagName, FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 1, null);
        store.put(flagName, 0, json(v1), "alice");

        long result = store.delete(flagName, 1, "alice");

        assertEquals(1L, result);
        assertNull(connection.sync().get(store.flagKey(flagName)), "the flag key must be gone after delete");
    }

    @Test
    void deleteAuditEntryCapturesTheDeletedValueAsBeforeAndEmptyAfter() {
        FlagDefinition v1 = new FlagDefinition(flagName, FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 1, null);
        String v1Json = json(v1);
        store.put(flagName, 0, v1Json, "alice");

        store.delete(flagName, 1, "bob");

        Map<String, String> deleteEntry = auditEntries().get(1).getBody();
        assertEquals("delete", deleteEntry.get("action"));
        assertEquals(v1Json, deleteEntry.get("before"));
        assertEquals("", deleteEntry.get("after"));
        assertEquals("bob", deleteEntry.get("actor"));
    }

    @Test
    void staleDeleteReturnsConflictAndWritesNoAuditEntryAndLeavesTheFlagInPlace() {
        FlagDefinition v1 = new FlagDefinition(flagName, FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 1, null);
        store.put(flagName, 0, json(v1), "alice");

        // Caller thinks the version is still 0 (stale read) — the real version is 1.
        long result = store.delete(flagName, 0, "mallory");

        assertEquals(-1L, result, "a stale delete must be rejected as a conflict");
        assertEquals(1, auditEntries().size(), "the rejected delete must not add a phantom audit entry");
        assertTrue(connection.sync().get(store.flagKey(flagName)) != null, "the flag must survive a rejected delete");
    }

    @Test
    void deletingAnAlreadyAbsentFlagAtVersionZeroIsANoOpSuccess() {
        long result = store.delete(flagName, 0, "alice");

        assertEquals(0L, result);
        // Still atomically audited: a delete-of-nothing is still a real, evidenced admin action.
        assertEquals(1, auditEntries().size());
        Map<String, String> body = auditEntries().get(0).getBody();
        assertEquals("delete", body.get("action"));
        // AUD-21: deleting nothing is not a successful mutation -- the audit must say so.
        assertEquals("noop", body.get("result"));
    }

    @Test
    void deleteOfAnExistingFlagStillAuditsResultOk() {
        FlagDefinition v1 = new FlagDefinition(flagName, FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 1, null);
        store.put(flagName, 0, json(v1), "alice");

        store.delete(flagName, 1, "alice");

        Map<String, String> body = auditEntries().get(1).getBody();
        assertEquals("delete", body.get("action"));
        assertEquals("ok", body.get("result"), "a delete that actually removed a flag must still audit as 'ok'");
    }

    @Test
    void concurrentCreatesAtTheSameExpectedVersionAllowExactlyOneWinner() throws InterruptedException {
        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            String actor = "actor-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    FlagDefinition candidate = new FlagDefinition(
                            flagName, FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 1, null);
                    long result = store.put(flagName, 0, json(candidate), actor);
                    if (result != -1L) {
                        successes.incrementAndGet();
                    }
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

        assertEquals(1, successes.get(), "exactly one concurrent create at the same expected version must win");
        assertEquals(1, auditEntries().size(), "only the winning mutation is audited, never the rejected ones");
    }
}
