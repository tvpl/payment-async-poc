package com.example.payments.api.redis;

import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.error.StoreUnavailableException;
import com.example.payments.api.idempotency.PublishState;
import com.example.payments.common.model.SimulationStatus;
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

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RES-01/RES-03: with Redis genuinely unreachable, every public method of {@link
 * RedisStatusStore} must fail closed with {@link StoreUnavailableException} instead of letting a
 * raw Lettuce exception (which carries the store's host and port) escape.
 *
 * <p>Redis is stopped for real after the application context boots against it — same technique
 * as {@code AdmissionRedisOutageIT} — so this exercises the actual driver failure mode, not a
 * mock standing in for one.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisStatusStoreOutageIT {

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
                "otel.traces.exporter", "none"));
        store = context.getBean(RedisStatusStore.class);
        // Prime a live connection before killing Redis, so every call below hits a genuinely
        // broken connection/timeout, not merely "never connected".
        store.get(UUID.randomUUID().toString());
        REDIS.stop();
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void saveFailsClosedWhenRedisIsUnreachable() {
        StatusEntry entry = new StatusEntry(UUID.randomUUID().toString(), SimulationStatus.PROCESSING, null);

        assertThrows(StoreUnavailableException.class, () -> store.save(entry));
    }

    @Test
    void getFailsClosedWhenRedisIsUnreachable() {
        assertThrows(StoreUnavailableException.class, () -> store.get(UUID.randomUUID().toString()));
    }

    @Test
    void reserveFailsClosedWhenRedisIsUnreachable() {
        assertThrows(StoreUnavailableException.class,
                () -> store.reserve("tenant-a", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "fp"));
    }

    @Test
    void markPublishStateFailsClosedWhenRedisIsUnreachable() {
        assertThrows(StoreUnavailableException.class, () -> store.markPublishState(
                "tenant-a", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "fp", PublishState.PUBLISHED));
    }

    @Test
    void publishResponseFailsClosedWhenRedisIsUnreachable() {
        assertThrows(StoreUnavailableException.class,
                () -> store.publishResponse(UUID.randomUUID().toString()));
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
