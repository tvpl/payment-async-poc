package com.example.payments.api.controller;

import com.example.payments.api.auth.DevTokenController;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task_T9 (AUD-16/AUD-27): the v0 surface, tested over real HTTP. (a) {@code paymentMethod}
 * outside {@code [A-Z_]{2,32}} must be rejected at the edge with 400 problem+json, same as any
 * other bean-validation failure ({@link com.example.payments.api.error.ValidationExceptionHandler}).
 * (b) a valid v0 response must no longer carry {@code X-Routed-Topic} - the header used to
 * announce routing via {@code TopicRouter} that the actual publish (always {@code Topics.REQUESTED})
 * never performed.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V0PaymentSimulationSurfaceIT {

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
    private String v0TesterToken;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        server = ApplicationContext.run(EmbeddedServer.class, properties());
        client = HttpClient.create(server.getURL());
        v0TesterToken = client.toBlocking().exchange(
                        HttpRequest.POST("/auth/token",
                                new DevTokenController.TokenRequest("v0-caller", List.of("v0-testers"))),
                        DevTokenController.TokenResponse.class)
                .body().accessToken();
    }

    @AfterAll
    void stop() {
        client.close();
        server.close();
    }

    @Test
    void rejectsAnOutOfPatternPaymentMethodWithProblemJson() {
        var invalid = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "credit-card!", "VISA", 1, "AUTHORIZE_AND_CAPTURE");

        HttpClientResponseException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/v0/payment-simulations", invalid)
                                .bearerAuth(v0TesterToken)
                                .header("Idempotency-Key", UUID.randomUUID().toString())));

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        assertEquals(Problem.MEDIA_TYPE, rejected.getResponse().getContentType().orElseThrow().toString());
        String body = rejected.getResponse().getBody(String.class).orElseThrow();
        assertTrue(body.contains("\"status\":400"));
    }

    @Test
    void aValidV0ResponseNeverCarriesARoutedTopicHeader() {
        var valid = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");

        HttpResponse<StatusResponse> response = client.toBlocking().exchange(
                HttpRequest.POST("/v0/payment-simulations", valid)
                        .bearerAuth(v0TesterToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                StatusResponse.class);

        assertNull(response.getHeaders().get("X-Routed-Topic"));
    }

    private Map<String, Object> properties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "payment.simulation.wait-timeout", "1s",
                "micronaut.security.token.jwt.signatures.secret.generator.secret", TEST_JWT_SECRET,
                "micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256");
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
