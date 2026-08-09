package com.example.payments.api;

import com.example.payments.api.dto.StatusResponse;
import com.redis.testcontainers.RedisContainer;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durable status fallback must never stretch a request past its budget, however slow the
 * SBUS is, and must stop paying that budget once the circuit trips (PAY-09).
 *
 * <p>The stub SBUS answers no faster than {@link #SBUS_DELAY}, which is far beyond the
 * configured read budget.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SbusFallbackBudgetIT {

    private static final String API_KEY = "test-only-api-key";
    private static final String TEST_JWT_SECRET = "test-only-api-signing-secret-with-at-least-32-bytes";

    private static final Duration SBUS_DELAY = Duration.ofSeconds(5);
    private static final Duration READ_BUDGET = Duration.ofMillis(500);
    private static final int FAILURE_THRESHOLD = 2;

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private static final AtomicInteger SBUS_CALLS = new AtomicInteger();

    private HttpServer slowSbus;
    private ExecutorService slowSbusExecutor;
    private EmbeddedServer server;
    private HttpClient client;

    @BeforeAll
    void start() throws IOException {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        slowSbus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowSbus.createContext("/internal/payment-simulations", exchange -> {
            SBUS_CALLS.incrementAndGet();
            try (InputStream body = exchange.getRequestBody()) {
                body.readAllBytes();
            }
            try {
                Thread.sleep(SBUS_DELAY.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        // One thread per call, so a queued request never hides a call that was actually made.
        slowSbusExecutor = Executors.newCachedThreadPool();
        slowSbus.setExecutor(slowSbusExecutor);
        slowSbus.start();

        server = ApplicationContext.run(EmbeddedServer.class, properties());
        client = HttpClient.create(server.getURL());
    }

    @AfterAll
    void stop() {
        client.close();
        server.close();
        slowSbus.stop(0);
        slowSbusExecutor.shutdownNow();
    }

    @Test
    void aSlowSbusNeverStretchesTheLookupBeyondItsReadBudget() {
        long start = System.nanoTime();
        HttpClientResponseException notFound =
                assertThrows(HttpClientResponseException.class, () -> lookup(UUID.randomUUID().toString()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatus());
        assertTrue(elapsed.compareTo(SBUS_DELAY) < 0,
                "lookup inherited the SBUS delay: " + elapsed);
    }

    @Test
    void aPersistentlyFailingSbusStopsCostingEveryRequestItsBudget() {
        for (int attempt = 0; attempt < FAILURE_THRESHOLD; attempt++) {
            assertThrows(HttpClientResponseException.class, () -> lookup(UUID.randomUUID().toString()));
        }
        int callsBeforeOpen = SBUS_CALLS.get();

        long start = System.nanoTime();
        assertThrows(HttpClientResponseException.class, () -> lookup(UUID.randomUUID().toString()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(callsBeforeOpen, SBUS_CALLS.get(),
                "an open circuit must not call the SBUS at all");
        assertTrue(elapsed.compareTo(READ_BUDGET) < 0,
                "an open circuit still paid the read budget: " + elapsed);
    }

    private void lookup(String requestId) {
        client.toBlocking().exchange(
                HttpRequest.GET("/payment-simulations/" + requestId).header("X-API-Key", API_KEY),
                StatusResponse.class);
    }

    private Map<String, Object> properties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("kafka.bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("redis.uri", REDIS.getRedisURI());
        properties.put("apicurio.registry.url", registryUrl());
        properties.put("otel.traces.exporter", "none");
        properties.put("micronaut.http.services.sbus.url",
                "http://127.0.0.1:" + slowSbus.getAddress().getPort());
        properties.put("micronaut.http.services.sbus.read-timeout", READ_BUDGET.toMillis() + "ms");
        properties.put("micronaut.http.services.sbus.connect-timeout", "500ms");
        properties.put("payment.sbus.failure-threshold", FAILURE_THRESHOLD);
        properties.put("payment.sbus.open-duration", "30s");
        properties.put("payment.security.api-keys", List.of(API_KEY));
        properties.put("micronaut.security.token.jwt.signatures.secret.generator.secret", TEST_JWT_SECRET);
        properties.put("micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256");
        return properties;
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
