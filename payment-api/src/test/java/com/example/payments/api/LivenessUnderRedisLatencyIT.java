package com.example.payments.api;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUDG-04: with the admission Redis round-trip latent (&gt;= 2s), {@code /health/liveness} must
 * still answer in under 500ms. Before T17 (filters off the Netty event loop), an admission
 * request blocking the event loop on that latent Redis call would have starved every other
 * connection the event loop serves, liveness included; {@code ConcurrencyLimitFilterOffEventLoopIT}
 * already proves the filter itself now runs off the event loop (BUDG-03), so this test's job is
 * only to prove the end-to-end, user-visible consequence of that fix.
 *
 * <p>The liveness probe uses the plain JDK client, not Micronaut's Netty-based one: a shared
 * Netty event-loop/connection-pool relationship between two Micronaut clients in the same process
 * could confound a server-side result with a client-side one. The JDK client has no such
 * relationship with Netty.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LivenessUnderRedisLatencyIT {

    private static final String API_KEY = "liveness-latency-tenant-key";
    private static final Duration REDIS_RESPONSE_DELAY = Duration.ofSeconds(2);
    private static final Duration LIVENESS_BUDGET = Duration.ofMillis(500);
    /** A handful of concurrent slow admissions in flight - enough to prove the point, not a load test. */
    private static final int CONCURRENT_ADMISSION_REQUESTS = 3;
    private static final int LIVENESS_SAMPLES = 6;
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(300);

    private static final PaymentSimulationRequest REQUEST = new PaymentSimulationRequest(
            "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private SlowRedisProxy redisProxy;
    private EmbeddedServer server;
    private HttpClient client;
    private java.net.http.HttpClient livenessProbeClient;
    private ExecutorService requesters;

    @BeforeAll
    void start() throws Exception {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        redisProxy = new SlowRedisProxy(REDIS.getRedisHost(), REDIS.getRedisPort(), REDIS_RESPONSE_DELAY);
        server = ApplicationContext.run(EmbeddedServer.class, properties());
        client = HttpClient.create(server.getURL());
        livenessProbeClient = java.net.http.HttpClient.newHttpClient();
        requesters = Executors.newFixedThreadPool(CONCURRENT_ADMISSION_REQUESTS);
    }

    @AfterAll
    void stop() {
        requesters.shutdownNow();
        client.close();
        server.close();
        redisProxy.close();
    }

    @Test
    void livenessStaysUnderBudgetWhileAdmissionRedisIsLatent() throws Exception {
        for (int i = 0; i < CONCURRENT_ADMISSION_REQUESTS; i++) {
            requesters.submit(this::submitIgnoringFailure);
        }
        // Let the admission requests actually reach the latent Redis call before measuring.
        Thread.sleep(300);

        java.net.http.HttpRequest livenessRequest = java.net.http.HttpRequest.newBuilder(
                        URI.create(server.getURL() + "/health/liveness"))
                .GET().build();
        for (int sample = 0; sample < LIVENESS_SAMPLES; sample++) {
            long startNanos = System.nanoTime();
            HttpResponse<Void> liveness =
                    livenessProbeClient.send(livenessRequest, HttpResponse.BodyHandlers.discarding());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

            assertEquals(200, liveness.statusCode());
            assertTrue(elapsed.compareTo(LIVENESS_BUDGET) < 0,
                    "liveness sample " + sample + " took " + elapsed + " while admission Redis was latent "
                            + "(budget " + LIVENESS_BUDGET + ")");
            Thread.sleep(SAMPLE_INTERVAL.toMillis());
        }
    }

    private void submitIgnoringFailure() {
        try {
            client.toBlocking().exchange(HttpRequest.POST("/payment-simulations", REQUEST)
                    .header("X-API-Key", API_KEY)
                    .header("Idempotency-Key", UUID.randomUUID().toString()));
        } catch (HttpClientResponseException ignored) {
            // only the event-loop starvation matters here, not how each request resolves
        }
    }

    private Map<String, Object> properties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("kafka.bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("redis.uri", "redis://127.0.0.1:" + redisProxy.port());
        properties.put("apicurio.registry.url", registryUrl());
        properties.put("otel.traces.exporter", "none");
        properties.put("payment.simulation.wait-timeout", "3s");
        properties.put("payment.security.api-keys", List.of(API_KEY));
        return properties;
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
