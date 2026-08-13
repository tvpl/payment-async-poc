package com.example.payments.api;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusResponse;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RES-01/RES-03/RES-04/RES-06: with Redis genuinely unreachable, {@code POST}/{@code GET} on
 * {@code /payment-simulations} fail closed with 503, never leak infrastructure detail, and the
 * application recovers on its own once Redis comes back — no restart.
 *
 * <p>Redis is <strong>paused</strong> (not stopped) via the Docker daemon directly: {@code docker
 * pause} suspends the container's process without dropping the TCP connection or freeing its
 * mapped port, so every command Lettuce sends simply hangs until its 2s command timeout ({@link
 * com.example.payments.api.redis.RedisClientTuning}) fires — a closer simulation of a stalled
 * dependency than stopping the container, and the only way to later prove recovery against the
 * SAME endpoint: stopping would hand a restarted container a new, unrelated port.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisOutageFailClosedIT {

    private static final String API_KEY = "outage-flow-key";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private static final PaymentSimulationRequest REQUEST = new PaymentSimulationRequest(
            "MERCHANT-001", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");

    private EmbeddedServer server;
    private HttpClient client;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        server = ApplicationContext.run(EmbeddedServer.class, properties());
        client = HttpClient.create(server.getURL());
    }

    @AfterAll
    void stop() {
        client.close();
        server.close();
    }

    @Test
    void postAndGetFailClosedDuringAnOutageAndRecoverOnceItEnds() {
        // Sanity: the flow works before the outage.
        HttpResponse<StatusResponse> before = post();
        assertEquals(HttpStatus.ACCEPTED, before.getStatus());

        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            HttpClientResponseException postRejected = assertThrows(HttpClientResponseException.class, this::post);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, postRejected.getStatus());
            assertNoInfrastructureLeak(postRejected.getResponse().getBody(String.class).orElse(""));

            // No SBUS reachable in this test either, so GET on a request the store can't answer
            // must also fail closed — never a false 404 claiming the request never existed.
            HttpClientResponseException getRejected = assertThrows(HttpClientResponseException.class,
                    () -> get(UUID.randomUUID().toString()));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, getRejected.getStatus());
            assertNoInfrastructureLeak(getRejected.getResponse().getBody(String.class).orElse(""));
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        // Recovery without a restart: the same running application, same connection/client,
        // accepts new work again once the dependency answers.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(HttpStatus.ACCEPTED, post().getStatus()));
    }

    private static void assertNoInfrastructureLeak(String body) {
        String lower = body.toLowerCase();
        assertFalse(lower.contains("redis"), "body must not name the infrastructure component: " + body);
        assertFalse(lower.contains("lettuce"), "body must not name the driver: " + body);
        assertFalse(body.contains(String.valueOf(REDIS.getMappedPort(6379))), "body must not leak the port: " + body);
    }

    private HttpResponse<StatusResponse> post() {
        return client.toBlocking().exchange(
                HttpRequest.POST("/payment-simulations", REQUEST)
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                StatusResponse.class);
    }

    private HttpResponse<StatusResponse> get(String requestId) {
        return client.toBlocking().exchange(
                HttpRequest.GET("/payment-simulations/" + requestId).header("X-API-Key", API_KEY),
                StatusResponse.class);
    }

    private Map<String, Object> properties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "payment.simulation.wait-timeout", "1s",
                "payment.security.api-keys", java.util.List.of(API_KEY));
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
