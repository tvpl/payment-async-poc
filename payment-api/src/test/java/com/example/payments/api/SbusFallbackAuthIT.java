package com.example.payments.api;

import com.example.payments.api.dto.StatusResponse;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.redis.testcontainers.RedisContainer;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SEC-05: the fallback call to the SBUS's {@code /internal/payment-simulations/{id}} must
 * present a {@code ROLE_PAYMENT_API} service credential, and a rejected credential must degrade
 * exactly like any other SBUS unavailability - never surface as an error to the caller - while
 * still being observable via a dedicated metric.
 *
 * <p>The stub SBUS below stands in for the real one (payment-api never depends on the payment-sbus
 * module, per AD-001/002) and enforces the same contract the real {@code InternalStatusController}
 * does: a Bearer JWT, signed with the shared secret, carrying {@code ROLE_PAYMENT_API} among its
 * {@code roles} claim.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SbusFallbackAuthIT {

    private static final String API_KEY = "test-only-api-key";
    private static final String TEST_JWT_SECRET = "test-only-api-signing-secret-with-at-least-32-bytes";
    private static final String SBUS_SHARED_SECRET = "test-only-sbus-shared-secret-with-at-least-32-bytes";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    @BeforeAll
    void startContainers() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
    }

    private HttpServer stubSbus;
    private EmbeddedServer server;
    private HttpClient client;

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (stubSbus != null) {
            stubSbus.stop(0);
        }
    }

    @Test
    void authenticatedFallbackReturns200WithTheRequiredRoleActive() throws IOException {
        startStubSbus();
        start(Map.of("payment.sbus.credential.secret", SBUS_SHARED_SECRET));

        StatusResponse body = lookup(UUID.randomUUID().toString());

        assertEquals("COMPLETED", body.status().name());
    }

    @Test
    void aRejectedCredentialDegradesToNoResultAndRecordsTheAuthFailureMetric() throws IOException {
        startStubSbus();
        // No payment.sbus.credential.* configured: the client filter adds no Authorization
        // header at all, so the stub SBUS (which requires one) rejects the call with 401.
        start(Map.of());

        HttpClientResponseException notFound =
                assertThrows(HttpClientResponseException.class, () -> lookup(UUID.randomUUID().toString()));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatus());

        MeterRegistry registry = server.getApplicationContext().getBean(MeterRegistry.class);
        assertEquals(1.0, registry.get("api_sbus_auth_failures_total").counter().count());
    }

    private void startStubSbus() throws IOException {
        stubSbus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubSbus.createContext("/internal/payment-simulations", exchange -> {
            String requestId = exchange.getRequestURI().getPath()
                    .substring(exchange.getRequestURI().getPath().lastIndexOf('/') + 1);
            if (!isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            String json = "{\"requestId\":\"" + requestId + "\",\"status\":\"COMPLETED\",\"result\":null}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        });
        stubSbus.start();
    }

    /** Mirrors the SBUS's own {@code @Secured("ROLE_PAYMENT_API")} check on the internal route. */
    private static boolean isAuthorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        try {
            SignedJWT jwt = SignedJWT.parse(authorizationHeader.substring("Bearer ".length()));
            if (!jwt.verify(new MACVerifier(SBUS_SHARED_SECRET.getBytes(StandardCharsets.UTF_8)))) {
                return false;
            }
            List<String> roles = jwt.getJWTClaimsSet().getStringListClaim("roles");
            return roles != null && roles.contains("ROLE_PAYMENT_API");
        } catch (Exception e) {
            return false;
        }
    }

    private StatusResponse lookup(String requestId) {
        return client.toBlocking().exchange(
                HttpRequest.GET("/payment-simulations/" + requestId).header("X-API-Key", API_KEY),
                StatusResponse.class).body();
    }

    private void start(Map<String, Object> extraProperties) {
        server = ApplicationContext.run(EmbeddedServer.class, properties(extraProperties));
        client = HttpClient.create(server.getURL());
    }

    private Map<String, Object> properties(Map<String, Object> extraProperties) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("kafka.bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("redis.uri", REDIS.getRedisURI());
        properties.put("apicurio.registry.url", registryUrl());
        properties.put("otel.traces.exporter", "none");
        properties.put("micronaut.http.services.sbus.url",
                "http://127.0.0.1:" + stubSbus.getAddress().getPort());
        properties.put("payment.security.api-keys", List.of(API_KEY));
        properties.put("micronaut.security.token.jwt.signatures.secret.generator.secret", TEST_JWT_SECRET);
        properties.put("micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256");
        properties.putAll(extraProperties);
        return properties;
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
