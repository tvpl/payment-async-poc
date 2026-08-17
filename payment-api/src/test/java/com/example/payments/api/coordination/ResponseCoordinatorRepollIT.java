package com.example.payments.api.coordination;

import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.model.SimulationStatus;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-01: with a real Redis, a terminal result written without a {@code PUBLISH} (a dropped
 * pub/sub notification, or a writer that crashed between the store write and the publish) must
 * still be found - and well before the full {@code wait-timeout} - by {@code await()}'s own
 * periodic re-poll, never surfacing as a false 202.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseCoordinatorRepollIT {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(3);

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private ApplicationContext context;
    private ResponseCoordinator coordinator;
    private RedisStatusStore store;
    private ScheduledExecutorService delayedWriter;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        context = ApplicationContext.run(properties());
        coordinator = context.getBean(ResponseCoordinator.class);
        store = context.getBean(RedisStatusStore.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @AfterEach
    void tearDown() {
        if (delayedWriter != null) {
            delayedWriter.shutdownNow();
        }
    }

    @Test
    void aTerminalResultWrittenWithoutAPublishIsStillFoundByThePeriodicRepoll() throws Exception {
        String requestId = "req-" + UUID.randomUUID();
        StatusEntry terminal = new StatusEntry(requestId, SimulationStatus.COMPLETED, null);
        CompletableFuture<StatusEntry> future = coordinator.register(requestId);

        // Simulates a writer that saved the result but whose Redis PUBLISH never arrived: only
        // the store gets the terminal entry directly. coordinator.complete()/onMessage() and
        // store.publishResponse() are deliberately never called - the wake-up is lost.
        delayedWriter = Executors.newSingleThreadScheduledExecutor();
        delayedWriter.schedule(() -> store.save(terminal), 700, TimeUnit.MILLISECONDS);

        long start = System.nanoTime();
        Optional<StatusEntry> result = coordinator.await(requestId, future);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(Optional.of(terminal), result);
        assertTrue(elapsed.compareTo(WAIT_TIMEOUT) < 0,
                "result only surfaced at/after the full wait-timeout, not via re-poll: " + elapsed);
    }

    private Map<String, Object> properties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "payment.simulation.wait-timeout", "3s");
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
