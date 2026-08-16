package com.example.payments.api.redis;

import com.example.payments.api.idempotency.IdempotencyOutcome;
import com.example.payments.api.idempotency.PublishState;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Redis proof that a reservation records whether its publish was confirmed, so an
 * unpublished identity is recovered instead of orphaned until it expires (PAY-03).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishStateReservationIT {

    private static final String TENANT = "tenant-a";
    private static final String IDEMPOTENCY_TTL = "6s";
    private static final long IDEMPOTENCY_TTL_MILLIS = 6_000L;
    private static final long PUBLISH_LEASE_MILLIS = 1_000L;

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private ApplicationContext context;
    private RedisStatusStore store;
    private RedisClient inspectorClient;
    private StatefulRedisConnection<String, String> inspector;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        context = ApplicationContext.run(Map.of(
                "micronaut.server.enabled", false,
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "payment.simulation.idempotency-ttl", IDEMPOTENCY_TTL,
                "payment.simulation.status-ttl", IDEMPOTENCY_TTL,
                "payment.simulation.publish-lease", "1s"));
        store = context.getBean(RedisStatusStore.class);
        inspectorClient = RedisClient.create(REDIS.getRedisURI());
        inspector = inspectorClient.connect();
    }

    @AfterAll
    void stop() {
        inspector.close();
        inspectorClient.shutdown();
        context.close();
    }

    @Test
    void anAttemptStillHoldingTheLeaseReplaysInsteadOfPublishingTwice() {
        String key = newKey();
        String owner = UUID.randomUUID().toString();

        store.reserve(TENANT, key, owner, "fp-a");
        IdempotencyOutcome outcome = store.reserve(TENANT, key, UUID.randomUUID().toString(), "fp-a");

        assertInstanceOf(IdempotencyOutcome.Replay.class, outcome);
        assertEquals(owner, ((IdempotencyOutcome.Replay) outcome).requestId());
    }

    @Test
    void anAttemptThatDiedWithoutPublishingBecomesResumableUnderTheSameRequestId()
            throws InterruptedException {
        String key = newKey();
        String owner = UUID.randomUUID().toString();

        store.reserve(TENANT, key, owner, "fp-a");
        Thread.sleep(PUBLISH_LEASE_MILLIS + 300);
        IdempotencyOutcome outcome = store.reserve(TENANT, key, UUID.randomUUID().toString(), "fp-a");

        assertInstanceOf(IdempotencyOutcome.ResumePublish.class, outcome);
        assertEquals(owner, ((IdempotencyOutcome.ResumePublish) outcome).requestId());
    }

    @Test
    void aReportedPublishFailureIsResumableWithoutWaitingOutTheLease() {
        String key = newKey();
        String owner = UUID.randomUUID().toString();

        store.reserve(TENANT, key, owner, "fp-a");
        store.markPublishState(TENANT, key, owner, "fp-a", PublishState.PUBLISH_FAILED);
        IdempotencyOutcome outcome = store.reserve(TENANT, key, UUID.randomUUID().toString(), "fp-a");

        assertInstanceOf(IdempotencyOutcome.ResumePublish.class, outcome);
        assertEquals(owner, ((IdempotencyOutcome.ResumePublish) outcome).requestId());
    }

    @Test
    void aConfirmedPublishStaysAPlainReplayEvenAfterTheLeaseLapses() throws InterruptedException {
        String key = newKey();
        String owner = UUID.randomUUID().toString();

        store.reserve(TENANT, key, owner, "fp-a");
        store.markPublishState(TENANT, key, owner, "fp-a", PublishState.PUBLISHED);
        Thread.sleep(PUBLISH_LEASE_MILLIS + 300);
        IdempotencyOutcome outcome = store.reserve(TENANT, key, UUID.randomUUID().toString(), "fp-a");

        assertInstanceOf(IdempotencyOutcome.Replay.class, outcome);
        assertEquals(owner, ((IdempotencyOutcome.Replay) outcome).requestId());
    }

    @Test
    void divergentPayloadStillConflictsWhileThePublishIsUnconfirmed() {
        String key = newKey();
        String owner = UUID.randomUUID().toString();

        store.reserve(TENANT, key, owner, "fp-a");
        store.markPublishState(TENANT, key, owner, "fp-a", PublishState.PUBLISH_FAILED);
        IdempotencyOutcome outcome = store.reserve(TENANT, key, UUID.randomUUID().toString(), "fp-b");

        assertInstanceOf(IdempotencyOutcome.Conflict.class, outcome);
        assertEquals(owner, ((IdempotencyOutcome.Conflict) outcome).requestId());
    }

    @Test
    void recordingThePublishOutcomeDoesNotExtendTheDedupWindow() throws InterruptedException {
        String key = newKey();
        String owner = UUID.randomUUID().toString();

        store.reserve(TENANT, key, owner, "fp-a");
        Thread.sleep(1_500);
        store.markPublishState(TENANT, key, owner, "fp-a", PublishState.PUBLISHED);

        long remaining = inspector.sync().pttl("idem:" + TENANT + ":" + key);
        assertTrue(remaining > 0, "reservation must survive the mark, was " + remaining);
        assertTrue(remaining <= IDEMPOTENCY_TTL_MILLIS - 1_400,
                "mark must keep the original expiry, remaining was " + remaining);
    }

    @Test
    void recordingThePublishOutcomeNeverResurrectsAnExpiredReservation() {
        String key = newKey();

        store.markPublishState(TENANT, key, UUID.randomUUID().toString(), "fp-a", PublishState.PUBLISHED);

        assertEquals(0L, inspector.sync().exists("idem:" + TENANT + ":" + key));
    }

    private static String newKey() {
        return "publish-state-" + UUID.randomUUID();
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
