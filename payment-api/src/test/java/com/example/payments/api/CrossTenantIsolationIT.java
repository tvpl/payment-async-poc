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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent Test for the P1 story "Isolamento de tenant e idempotência obrigatória": two
 * distinct API keys, each bound to its own tenant. Same key+payload from two tenants never
 * replays across them; a divergent payload only conflicts for the owning tenant; a missing
 * Idempotency-Key is always 400; a declared tenant outside the credential's binding is always
 * 403 (TEN-04).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossTenantIsolationIT {

    private static final String TENANT_A_KEY = "cross-tenant-key-a";
    private static final String TENANT_B_KEY = "cross-tenant-key-b";
    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
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

    /** Same Idempotency-Key + same payload from two tenants -> two independent requestIds. */
    @Test
    void sameKeyAndPayloadAcrossTenantsProduceIndependentRequestIds() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentSimulationRequest request = request();

        String requestIdA = submit(TENANT_A_KEY, idempotencyKey, request).body().requestId();
        String requestIdB = submit(TENANT_B_KEY, idempotencyKey, request).body().requestId();

        assertNotEquals(requestIdA, requestIdB);
    }

    /** A replay (same key+payload) never crosses tenants: each tenant only ever sees its own owner. */
    @Test
    void replayOnlyEverReturnsTheOwningTenantsOwnRequestId() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentSimulationRequest request = request();

        String requestIdA = submit(TENANT_A_KEY, idempotencyKey, request).body().requestId();
        String requestIdB = submit(TENANT_B_KEY, idempotencyKey, request).body().requestId();

        String replayA = submit(TENANT_A_KEY, idempotencyKey, request).body().requestId();
        String replayB = submit(TENANT_B_KEY, idempotencyKey, request).body().requestId();

        assertEquals(requestIdA, replayA);
        assertEquals(requestIdB, replayB);
    }

    /** Same key, divergent payload, same tenant -> 409 for the owner. */
    @Test
    void divergentPayloadSameTenantConflicts() {
        String idempotencyKey = UUID.randomUUID().toString();
        submit(TENANT_A_KEY, idempotencyKey, request());

        HttpClientResponseException conflict = assertThrows(HttpClientResponseException.class,
                () -> submit(TENANT_A_KEY, idempotencyKey, divergentRequest()));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
    }

    /**
     * TEN-04: the same key, ANY payload (same or divergent), from a different tenant never
     * conflicts against the other tenant's reservation - no 409 derived from tenant A leaks to
     * tenant B.
     */
    @Test
    void sameKeyDivergentPayloadOnAnotherTenantNeverConflictsAgainstTheFirstTenant() {
        String idempotencyKey = UUID.randomUUID().toString();
        submit(TENANT_A_KEY, idempotencyKey, request());

        HttpResponse<StatusResponse> response = submit(TENANT_B_KEY, idempotencyKey, divergentRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatus());
    }

    /** IDEM-01: missing Idempotency-Key is always 400, regardless of tenant. */
    @Test
    void missingIdempotencyKeyIs400ForEitherTenant() {
        HttpClientResponseException failureA = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/payment-simulations", request()).header("X-API-Key", TENANT_A_KEY),
                        StatusResponse.class));
        HttpClientResponseException failureB = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/payment-simulations", request()).header("X-API-Key", TENANT_B_KEY),
                        StatusResponse.class));

        assertEquals(HttpStatus.BAD_REQUEST, failureA.getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, failureB.getStatus());
    }

    /** TEN-01/TEN-04: a forged X-Tenant-Id outside the credential's own binding is always 403. */
    @Test
    void forgedTenantHeaderOutsideTheCredentialsBindingIs403() {
        HttpClientResponseException forgedByA = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/payment-simulations", request())
                                .header("X-API-Key", TENANT_A_KEY)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .header("X-Tenant-Id", TENANT_B),
                        StatusResponse.class));

        assertEquals(HttpStatus.FORBIDDEN, forgedByA.getStatus());
        assertEquals(Problem.MEDIA_TYPE, forgedByA.getResponse().getContentType().orElseThrow().toString());
        assertTrue(forgedByA.getResponse().getBody(String.class).orElseThrow().contains("\"status\":403"));
    }

    private HttpResponse<StatusResponse> submit(String apiKey, String idempotencyKey, PaymentSimulationRequest request) {
        return client.toBlocking().exchange(
                HttpRequest.POST("/payment-simulations", request)
                        .header("X-API-Key", apiKey)
                        .header("Idempotency-Key", idempotencyKey),
                StatusResponse.class);
    }

    private static PaymentSimulationRequest request() {
        return new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("50.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
    }

    private static PaymentSimulationRequest divergentRequest() {
        return new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("999.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
    }

    private static Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("redis.uri", REDIS.getRedisURI()),
                Map.entry("apicurio.registry.url", registryUrl()),
                Map.entry("otel.traces.exporter", "none"),
                Map.entry("payment.simulation.wait-timeout", "1s"),
                Map.entry("payment.security.api-keys", List.of(TENANT_A_KEY, TENANT_B_KEY)),
                Map.entry("payment.security.tenants." + hash(TENANT_A_KEY), List.of(TENANT_A)),
                Map.entry("payment.security.tenants." + hash(TENANT_B_KEY), List.of(TENANT_B)),
                Map.entry("micronaut.security.token.jwt.signatures.secret.generator.secret", TEST_JWT_SECRET),
                Map.entry("micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256"));
    }

    private static String hash(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
