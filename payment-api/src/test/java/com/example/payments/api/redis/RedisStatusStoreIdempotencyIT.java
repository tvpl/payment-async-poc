package com.example.payments.api.redis;

import com.example.payments.api.idempotency.IdempotencyOutcome;
import com.redis.testcontainers.RedisContainer;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Redis proof of the atomic reserve/replay/conflict contract, scoped per tenant
 * (PAY-01/PAY-02, TEN-04, TEN-05, IDEM-03).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisStatusStoreIdempotencyIT {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private ApplicationContext context;
    private RedisStatusStore store;

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
                "payment.simulation.idempotency-ttl", "2s",
                "payment.simulation.status-ttl", "2s"));
        store = context.getBean(RedisStatusStore.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void firstUseOfAKeyIsReserved() {
        IdempotencyOutcome outcome = store.reserve(TENANT_A, newKey(), UUID.randomUUID().toString(), "fp-a");

        assertInstanceOf(IdempotencyOutcome.Reserved.class, outcome);
    }

    @Test
    void sameKeyAndFingerprintReplaysTheOriginalIdentity() {
        String key = newKey();
        String firstRequestId = UUID.randomUUID().toString();

        store.reserve(TENANT_A, key, firstRequestId, "fp-a");
        IdempotencyOutcome outcome = store.reserve(TENANT_A, key, UUID.randomUUID().toString(), "fp-a");

        assertInstanceOf(IdempotencyOutcome.Replay.class, outcome);
        assertEquals(firstRequestId, ((IdempotencyOutcome.Replay) outcome).requestId());
    }

    @Test
    void sameKeyDifferentFingerprintIsADeterministicConflict() {
        String key = newKey();
        String firstRequestId = UUID.randomUUID().toString();

        store.reserve(TENANT_A, key, firstRequestId, "fp-a");
        IdempotencyOutcome outcome = store.reserve(TENANT_A, key, UUID.randomUUID().toString(), "fp-b");

        assertInstanceOf(IdempotencyOutcome.Conflict.class, outcome);
        assertEquals(firstRequestId, ((IdempotencyOutcome.Conflict) outcome).requestId());
    }

    @Test
    void repeatedConflictingAttemptsNeverOverwriteTheOriginalOwner() {
        String key = newKey();
        String firstRequestId = UUID.randomUUID().toString();

        store.reserve(TENANT_A, key, firstRequestId, "fp-a");
        store.reserve(TENANT_A, key, UUID.randomUUID().toString(), "fp-b");
        IdempotencyOutcome outcome = store.reserve(TENANT_A, key, UUID.randomUUID().toString(), "fp-c");

        assertInstanceOf(IdempotencyOutcome.Conflict.class, outcome);
        assertEquals(firstRequestId, ((IdempotencyOutcome.Conflict) outcome).requestId());
    }

    @Test
    void reservationExpiresAfterConfiguredTtl() throws InterruptedException {
        String key = newKey();
        String firstRequestId = UUID.randomUUID().toString();

        store.reserve(TENANT_A, key, firstRequestId, "fp-a");
        Thread.sleep(2500);
        IdempotencyOutcome outcome = store.reserve(TENANT_A, key, UUID.randomUUID().toString(), "fp-a");

        assertInstanceOf(IdempotencyOutcome.Reserved.class, outcome);
    }

    @Test
    void distinctKeysReserveIndependently() {
        IdempotencyOutcome first = store.reserve(TENANT_A, newKey(), UUID.randomUUID().toString(), "fp-a");
        IdempotencyOutcome second = store.reserve(TENANT_A, newKey(), UUID.randomUUID().toString(), "fp-a");

        assertTrue(first instanceof IdempotencyOutcome.Reserved && second instanceof IdempotencyOutcome.Reserved);
    }

    /**
     * TEN-04: the same key and the same payload fingerprint from two different tenants must
     * never collide - each tenant gets its own reservation and its own requestId, never a
     * result, requestId, or 409 derived from the other tenant.
     */
    @Test
    void sameKeyAndFingerprintAcrossTenantsReserveIndependently() {
        String key = newKey();
        String requestIdA = UUID.randomUUID().toString();
        String requestIdB = UUID.randomUUID().toString();

        IdempotencyOutcome outcomeA = store.reserve(TENANT_A, key, requestIdA, "fp-shared");
        IdempotencyOutcome outcomeB = store.reserve(TENANT_B, key, requestIdB, "fp-shared");

        assertInstanceOf(IdempotencyOutcome.Reserved.class, outcomeA);
        assertInstanceOf(IdempotencyOutcome.Reserved.class, outcomeB);
        assertNotEquals(requestIdA, requestIdB);

        // A replay against each tenant only ever returns that tenant's own owner.
        IdempotencyOutcome replayA = store.reserve(TENANT_A, key, UUID.randomUUID().toString(), "fp-shared");
        IdempotencyOutcome replayB = store.reserve(TENANT_B, key, UUID.randomUUID().toString(), "fp-shared");
        assertEquals(requestIdA, ((IdempotencyOutcome.Replay) replayA).requestId());
        assertEquals(requestIdB, ((IdempotencyOutcome.Replay) replayB).requestId());
    }

    /** TEN-04: a divergent payload on tenant B never conflicts against tenant A's reservation. */
    @Test
    void divergentPayloadOnOneTenantNeverConflictsAgainstAnotherTenantsReservation() {
        String key = newKey();
        store.reserve(TENANT_A, key, UUID.randomUUID().toString(), "fp-a");

        IdempotencyOutcome outcome = store.reserve(TENANT_B, key, UUID.randomUUID().toString(), "fp-b");

        assertInstanceOf(IdempotencyOutcome.Reserved.class, outcome);
    }

    private static String newKey() {
        return "idem-test-" + UUID.randomUUID();
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
