package com.example.platform.asyncredis.worker;

import com.example.platform.asyncredis.queue.JobQueue;
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
 *
 * <p>Redis only lists a consumer once it has actually been handed a delivery - merely calling {@code
 * XREADGROUP} on an empty backlog registers nothing (verified against a live Redis 7.0.15: {@code
 * XINFO CONSUMERS} stays empty through a blocked, timed-out read with no messages). Each test
 * therefore feeds the stream enough entries, spread across enough delivery rounds, that every worker
 * of every instance under test is certain to receive at least one before the group is inspected.
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

            // Two workers race for deliveries; enough volume across enough rounds (cap 16/read) means
            // neither can starve the other before both get at least one turn.
            pushDummyJobs(40);

            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
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

            // Four workers share one group; enough volume that all four get at least one delivery.
            pushDummyJobs(120);

            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        Set<String> names = registeredConsumers();
                        Set<String> expected =
                                Set.of("inst-one-w0", "inst-one-w1", "inst-two-w0", "inst-two-w1");
                        assertTrue(names.containsAll(expected),
                                "expected " + expected + " but Redis registered " + names);
                    });
        }
    }

    /** Adds plain entries a worker will deliver-and-ack; the id is what makes each delivery real. */
    private static void pushDummyJobs(int count) {
        for (int i = 0; i < count; i++) {
            Map<String, String> body = new HashMap<>();
            body.put(JobQueue.FIELD_JOB_ID, UUID.randomUUID().toString());
            body.put(JobQueue.FIELD_REFERENCE, "identity-it");
            body.put(JobQueue.FIELD_AMOUNT, "100");
            conn.sync().xadd(STREAM, body);
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
