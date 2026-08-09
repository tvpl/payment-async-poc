package com.example.payments.api;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusResponse;
import com.example.payments.api.error.Problem;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end proof (real HTTP) that idempotency replay/conflict behave as PAY-01/PAY-02 require. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyIT {

    private static final String API_KEY = "test-only-api-key";
    private static final String TEST_JWT_SECRET = "test-only-api-signing-secret-with-at-least-32-bytes";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

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
    void sameKeyAndPayloadReplaysTheSameRequestId() {
        String idempotencyKey = UUID.randomUUID().toString();
        var request = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");

        String firstRequestId = submit(request, idempotencyKey).body().requestId();
        String secondRequestId = submit(request, idempotencyKey).body().requestId();

        assertEquals(firstRequestId, secondRequestId);
    }

    @Test
    void sameKeyDifferentPayloadReturnsConflictWithoutTouchingTheOriginal() {
        String idempotencyKey = UUID.randomUUID().toString();
        var original = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("50.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
        var divergent = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("999.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");

        String requestId = submit(original, idempotencyKey).body().requestId();

        HttpClientResponseException conflict = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class, () -> submit(divergent, idempotencyKey));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        String body = conflict.getResponse().getBody(String.class).orElseThrow();
        assertEquals(Problem.MEDIA_TYPE, conflict.getResponse().getContentType().orElseThrow().toString());
        assertTrue(body.contains("\"status\":409"));

        // The original reservation/status is untouched by the rejected divergent attempt.
        HttpResponse<StatusResponse> status = client.toBlocking().exchange(
                HttpRequest.GET("/payment-simulations/" + requestId).header("X-API-Key", API_KEY),
                StatusResponse.class);
        assertNotNull(status.body());
        assertEquals(requestId, status.body().requestId());
    }

    private HttpResponse<StatusResponse> submit(PaymentSimulationRequest request, String idempotencyKey) {
        return client.toBlocking().exchange(
                HttpRequest.POST("/payment-simulations", request)
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", idempotencyKey),
                StatusResponse.class);
    }

    private static Map<String, Object> properties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "payment.simulation.wait-timeout", "1s",
                "payment.security.api-keys", java.util.List.of(API_KEY),
                "micronaut.security.token.jwt.signatures.secret.generator.secret", TEST_JWT_SECRET,
                "micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256");
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
