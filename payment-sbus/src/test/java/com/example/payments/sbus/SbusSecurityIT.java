package com.example.payments.sbus;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SbusSecurityIT {

    private static final String TEST_SECRET = "test-only-sbus-signing-secret-with-at-least-32-bytes";
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    private EmbeddedServer server;
    private HttpClient client;
    private TokenGenerator tokenGenerator;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
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
    void rejectsAnonymousInternalRequest() {
        assertEquals(HttpStatus.UNAUTHORIZED, status(HttpRequest.GET(internalPath())));
    }

    @Test
    void rejectsMalformedBearerToken() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                status(HttpRequest.GET(internalPath()).bearerAuth("not-a-jwt")));
    }

    @Test
    void rejectsAuthenticatedCallerWithoutServiceRole() {
        assertEquals(HttpStatus.FORBIDDEN,
                status(HttpRequest.GET(internalPath()).bearerAuth(token("ROLE_USER"))));
    }

    @Test
    void acceptsPaymentApiServiceIdentity() {
        assertEquals(HttpStatus.NOT_FOUND,
                status(HttpRequest.GET(internalPath()).bearerAuth(token("ROLE_PAYMENT_API"))));
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
                "sub", "payment-api-test",
                "roles", List.of(role),
                "iat", now,
                "exp", now + 300))
                .orElseThrow();
    }

    private static String internalPath() {
        return "/internal/payment-simulations/missing-request";
    }

    private static Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("micronaut.server.port", -1),
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("apicurio.registry.url", registryUrl()),
                Map.entry("redis.uri", REDIS.getRedisURI()),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl() + "?stringtype=unspecified"),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("otel.traces.exporter", "none"),
                Map.entry("micronaut.security.token.jwt.signatures.secret.generator.secret", TEST_SECRET),
                Map.entry("micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256"));
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080)
                + "/apis/registry/v2";
    }
}
