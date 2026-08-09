package com.example.platform.asyncredis.worker;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-04 observed where it matters: in Redis's own consumer registry. Each running worker must show
 * up in {@code XINFO CONSUMERS} under its own name, and two instances of the service must add four
 * consumers rather than collapsing onto two shared ones.
 */
class WorkerConsumerIdentityIT {

    private static final String STREAM = "identity-it.jobs." + UUID.randomUUID();
    private static final String GROUP = "identity-it.workers";

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> conn;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://localhost:6379");
        conn = client.connect();
    }

    @AfterAll
    static void disconnect() {
        conn.sync().del(STREAM);
        conn.close();
        client.shutdown();
    }

    private static Map<String, Object> properties(String instanceId) {
        Map<String, Object> props = new HashMap<>();
        props.put("async.redis.security.enabled", false);
        props.put("async.redis.stream", STREAM);
        props.put("async.redis.group", GROUP);
        props.put("async.redis.worker-concurrency", 2);
        props.put("async.redis.reclaim-interval", "200ms");
        props.put("async.redis.reclaim-lease", "2s");
        if (instanceId != null) {
            props.put("async.redis.instance-id", instanceId);
        }
        return props;
    }

    @Test
    void eachWorkerOfAnInstanceRegistersAsItsOwnConsumer() {
        try (ApplicationContext ctx = ApplicationContext.run(properties("alpha"))) {
            WorkerIdentity identity = ctx.getBean(WorkerIdentity.class);
            assertEquals("alpha", identity.instanceId());

            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        Set<String> names = registeredConsumers();
                        assertTrue(names.contains("alpha-w0"), "missing alpha-w0; registered=" + names);
                        assertTrue(names.contains("alpha-w1"), "missing alpha-w1; registered=" + names);
                    });
        }
    }

    @Test
    void twoInstancesRegisterFourDistinctConsumersInsteadOfSharingTwo() {
        try (ApplicationContext first = ApplicationContext.run(properties("inst-one"));
             ApplicationContext second = ApplicationContext.run(properties("inst-two"))) {

            assertEquals("inst-one", first.getBean(WorkerIdentity.class).instanceId());
            assertEquals("inst-two", second.getBean(WorkerIdentity.class).instanceId());

            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        Set<String> names = registeredConsumers();
                        Set<String> expected =
                                Set.of("inst-one-w0", "inst-one-w1", "inst-two-w0", "inst-two-w1");
                        assertTrue(names.containsAll(expected),
                                "expected " + expected + " but Redis registered " + names);
                    });
        }
    }

    /** Consumer names Redis itself has on record for the group. */
    private static Set<String> registeredConsumers() {
        Set<String> names = new HashSet<>();
        for (Object entry : conn.sync().xinfoConsumers(STREAM, GROUP)) {
            if (entry instanceof List<?> fields) {
                for (int i = 0; i + 1 < fields.size(); i += 2) {
                    if ("name".equals(fields.get(i))) {
                        names.add(String.valueOf(fields.get(i + 1)));
                    }
                }
            }
        }
        return names;
    }
}
