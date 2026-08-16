package com.example.payments.api;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.dto.StatusResponse;
import com.example.payments.api.error.Problem;
import com.example.payments.api.idempotency.IdempotencyReservation;
import com.example.payments.api.idempotency.PublishState;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof (real HTTP, real Redis, dead broker) that a failed initial publish leaves
 * recoverable, retry-safe state and never an orphan that simulates processing (PAY-03).
 *
 * <p>Kafka is started so the application boots normally and then stopped, which is what an
 * outage looks like from the producer's side.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishFailureIT {

    private static final String API_KEY = "test-only-api-key";
    private static final String TEST_JWT_SECRET = "test-only-api-signing-secret-with-at-least-32-bytes";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration PUBLISH_BUDGET = Duration.ofMillis(500);

    private static final PaymentSimulationRequest REQUEST = new PaymentSimulationRequest(
            "MERCHANT-001", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private EmbeddedServer server;
    private HttpClient client;
    private ObjectMapper objectMapper;
    private RedisClient inspectorClient;
    private StatefulRedisConnection<String, String> inspector;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        server = ApplicationContext.run(EmbeddedServer.class, properties());
        client = HttpClient.create(server.getURL());
        objectMapper = server.getApplicationContext().getBean(ObjectMapper.class);
        inspectorClient = RedisClient.create(REDIS.getRedisURI());
        inspector = inspectorClient.connect();
        KAFKA.stop();
    }

    @AfterAll
    void stop() {
        inspector.close();
        inspectorClient.shutdown();
        client.close();
        server.close();
    }

    @Test
    void aFailedPublishIsReportedAsUnavailableInsteadOfAcceptedWork() {
        HttpClientResponseException failure =
                assertThrows(HttpClientResponseException.class, () -> submit(UUID.randomUUID().toString()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        assertEquals(Problem.MEDIA_TYPE,
                failure.getResponse().getContentType().orElseThrow().toString());
        assertTrue(failure.getResponse().getBody(String.class).orElseThrow().contains("\"status\":503"));
    }

    /**
     * BUDG-01/BUDG-02: with a dead broker, the derived publish budget (not Kafka's multi-tens-of
     * -seconds defaults) must bound the producer, so the 503 lands within wait-timeout + 1s.
     */
    @Test
    void aFailedPublishRespondsWithin503WithinTheWaitTimeoutPlusOneSecond() {
        long start = System.nanoTime();
        assertThrows(HttpClientResponseException.class, () -> submit(UUID.randomUUID().toString()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        Duration bound = WAIT_TIMEOUT.plusSeconds(1);
        assertTrue(elapsed.compareTo(bound) < 0,
                "503 took " + elapsed + ", which exceeds wait-timeout + 1s (" + bound + ")");
    }

    @Test
    void aFailedPublishLeavesTheReservationMarkedUnpublished() {
        String key = UUID.randomUUID().toString();

        assertThrows(HttpClientResponseException.class, () -> submit(key));

        IdempotencyReservation reservation = reservation(key);
        assertEquals(PublishState.PUBLISH_FAILED, reservation.publishState());
        assertNotNull(reservation.requestId());
    }

    @Test
    void aFailedPublishNeverLeavesAStatusThatSimulatesProcessing() {
        String key = UUID.randomUUID().toString();

        assertThrows(HttpClientResponseException.class, () -> submit(key));
        String requestId = reservation(key).requestId();

        StatusEntry entry = statusEntry(requestId);
        assertEquals("PENDING", entry.status().name());
    }

    @Test
    void retryingAfterAFailedPublishKeepsTheSameRequestId() {
        String key = UUID.randomUUID().toString();

        assertThrows(HttpClientResponseException.class, () -> submit(key));
        String firstRequestId = reservation(key).requestId();

        assertThrows(HttpClientResponseException.class, () -> submit(key));
        String secondRequestId = reservation(key).requestId();

        assertEquals(firstRequestId, secondRequestId);
    }

    @Test
    void aDifferentKeyAfterAFailedPublishGetsItsOwnIdentity() {
        String firstKey = UUID.randomUUID().toString();
        String secondKey = UUID.randomUUID().toString();

        assertThrows(HttpClientResponseException.class, () -> submit(firstKey));
        assertThrows(HttpClientResponseException.class, () -> submit(secondKey));

        assertNotEquals(reservation(firstKey).requestId(), reservation(secondKey).requestId());
    }

    private void submit(String idempotencyKey) {
        client.toBlocking().exchange(
                HttpRequest.POST("/payment-simulations", REQUEST)
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", idempotencyKey),
                StatusResponse.class);
    }

    private IdempotencyReservation reservation(String idempotencyKey) {
        // The API key above has no payment.security.tenants entry, so TenantResolver falls back
        // to the implicit "default" tenant (see TenantResolver javadoc), and the reservation is
        // keyed accordingly.
        String raw = inspector.sync().get("idem:default:" + idempotencyKey);
        assertNotNull(raw, "no reservation stored for " + idempotencyKey);
        try {
            return objectMapper.readValue(raw, IdempotencyReservation.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable reservation: " + raw, e);
        }
    }

    private StatusEntry statusEntry(String requestId) {
        String raw = inspector.sync().get("payment-simulation:" + requestId);
        assertNotNull(raw, "no status stored for " + requestId);
        try {
            return objectMapper.readValue(raw, StatusEntry.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable status entry: " + raw, e);
        }
    }

    private static Map<String, Object> properties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("kafka.bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("kafka.health.enabled", false);
        // BUDG-01: max.block.ms/request.timeout.ms/delivery.timeout.ms are derived from this
        // single budget (PublishBudgetProducerCustomizer), not set directly.
        properties.put("payment.publish-budget", PUBLISH_BUDGET.toMillis() + "ms");
        properties.put("redis.uri", REDIS.getRedisURI());
        properties.put("apicurio.registry.url", registryUrl());
        properties.put("otel.traces.exporter", "none");
        properties.put("payment.simulation.wait-timeout", WAIT_TIMEOUT.toMillis() + "ms");
        properties.put("payment.simulation.publish-lease", "30s");
        properties.put("payment.security.api-keys", List.of(API_KEY));
        properties.put("micronaut.security.token.jwt.signatures.secret.generator.secret", TEST_JWT_SECRET);
        properties.put("micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256");
        return properties;
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
