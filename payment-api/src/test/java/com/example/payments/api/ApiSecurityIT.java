package com.example.payments.api;

import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.token.generator.TokenGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Route-level AuthN/AuthZ matrix for {@code payment-api} (SEC-04/SEC-05): business, admin, v0,
 * dev-token and management endpoints, outside the production profile (mirrors payment-sbus's
 * SbusSecurityIT — the intercept-url-map under test lives in the base application.yml, so the
 * default/test environment already exercises the real policy).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiSecurityIT {

    private static final String TEST_SECRET = "test-only-api-signing-secret-with-at-least-32-bytes";
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private EmbeddedServer server;
    private HttpClient client;
    private TokenGenerator tokenGenerator;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        server = ApplicationContext.run(EmbeddedServer.class, properties());
        client = HttpClient.create(server.getURL());
        tokenGenerator = server.getApplicationContext().getBean(TokenGenerator.class);
    }

    @AfterAll
    void stop() {
        client.close();
        server.close();
    }

    @Test
    void rejectsAnonymousAdminRequest() {
        assertEquals(HttpStatus.UNAUTHORIZED, status(HttpRequest.DELETE(adminPath())));
    }

    @Test
    void rejectsMalformedBearerTokenOnAdminRequest() {
        assertEquals(HttpStatus.UNAUTHORIZED, status(HttpRequest.DELETE(adminPath()).bearerAuth("not-a-jwt")));
    }

    @Test
    void rejectsAdminRequestWithoutAdminRole() {
        assertEquals(HttpStatus.FORBIDDEN, status(HttpRequest.DELETE(adminPath()).bearerAuth(token("ROLE_USER"))));
    }

    @Test
    void acceptsAdminRoleForFeatureDeletion() {
        assertEquals(HttpStatus.NO_CONTENT, status(HttpRequest.DELETE(adminPath()).bearerAuth(token("ROLE_ADMIN"))));
    }

    @Test
    void rejectsBusinessEndpointWithoutApiKey() {
        assertEquals(HttpStatus.UNAUTHORIZED, status(HttpRequest.GET("/payment-simulations/missing-request")));
    }

    @Test
    void v0EndpointReachesFeatureGateAnonymouslyAtSecurityLayer() {
        // The security layer allows the request through (v0 self-enforces via feature-control);
        // a non-eligible/anonymous caller gets 404 from the controller, never 401.
        assertEquals(HttpStatus.NOT_FOUND, status(HttpRequest.GET("/v0/payment-simulations/missing-request")));
    }

    @Test
    void devTokenRouteIssuesTokensOutsideProduction() {
        var response = client.toBlocking().exchange(
                HttpRequest.POST("/auth/token", Map.of("userId", "someone", "groups", List.of("v0-testers"))),
                Map.class);
        assertEquals(HttpStatus.OK, response.getStatus());
    }

    @Test
    void exposesLivenessAnonymously() {
        assertEquals(HttpStatus.OK, status(HttpRequest.GET("/health/liveness")));
    }

    @Test
    void exposesReadinessAnonymously() {
        assertEquals(HttpStatus.OK, status(HttpRequest.GET("/health/readiness")));
    }

    @Test
    void protectsAggregateHealthDetails() {
        assertEquals(HttpStatus.UNAUTHORIZED, status(HttpRequest.GET("/health")));
    }

    @Test
    void protectsPrometheusMetrics() {
        assertEquals(HttpStatus.UNAUTHORIZED, status(HttpRequest.GET("/prometheus")));
    }

    @Test
    void disablesUnlistedManagementEndpoints() {
        // endpoints.all.enabled=false: only health/prometheus are registered, everything else 404s.
        assertEquals(HttpStatus.NOT_FOUND, status(HttpRequest.GET("/beans").bearerAuth(token("ROLE_ADMIN"))));
        assertEquals(HttpStatus.NOT_FOUND, status(HttpRequest.GET("/env").bearerAuth(token("ROLE_ADMIN"))));
    }

    private HttpStatus status(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request).getStatus();
        } catch (HttpClientResponseException exception) {
            return exception.getStatus();
        }
    }

    private String token(String role) {
        long now = Instant.now().getEpochSecond();
        return tokenGenerator.generateToken(Map.of(
                        "sub", "test-caller",
                        "roles", List.of(role),
                        "iat", now,
                        "exp", now + 300))
                .orElseThrow();
    }

    private static String adminPath() {
        return "/admin/features/does-not-exist";
    }

    private static Map<String, Object> properties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "micronaut.security.token.jwt.signatures.secret.generator.secret", TEST_SECRET,
                "micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256");
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
