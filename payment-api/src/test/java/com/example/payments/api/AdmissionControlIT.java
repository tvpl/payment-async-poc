package com.example.payments.api;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusResponse;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Admission control over real HTTP: an approved burst is accepted (202) and anything beyond
 * the budget is rejected with 429 and a {@code Retry-After}, per resource and per tenant
 * (CAP-03). Nothing is silently queued.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdmissionControlIT {

    /** Admission runs after authentication, so every tenant used here is a real API key. */
    private static final List<String> TENANTS =
            List.of("tenant-1-key", "tenant-2-key", "tenant-3-key", "tenant-4-key", "tenant-5-key");

    private static final int RESOURCE_BUDGET = 6;
    private static final int TENANT_BUDGET = 2;

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
    private RedisClient budgetClient;
    private StatefulRedisConnection<String, String> budgets;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        server = ApplicationContext.run(EmbeddedServer.class, properties());
        client = HttpClient.create(server.getURL());
        budgetClient = RedisClient.create(REDIS.getRedisURI());
        budgets = budgetClient.connect();
    }

    @AfterAll
    void stop() {
        budgets.close();
        budgetClient.shutdown();
        client.close();
        server.close();
    }

    /**
     * The window is deliberately long so a test is never rescued by a rollover; each test
     * therefore starts from a budget it fully owns.
     */
    @BeforeEach
    void resetBudgets() {
        budgets.sync().flushall();
    }

    @Test
    void aRequestWithinTheBudgetIsAccepted() {
        HttpResponse<StatusResponse> accepted = submit(TENANTS.get(0));

        assertEquals(HttpStatus.ACCEPTED, accepted.getStatus());
    }

    @Test
    void aBurstBeyondTheTenantBudgetIsRejectedWith429AndRetryAfter() {
        String tenant = TENANTS.get(0);

        for (int admitted = 0; admitted < TENANT_BUDGET; admitted++) {
            assertEquals(HttpStatus.ACCEPTED, submit(tenant).getStatus());
        }
        HttpClientResponseException rejected =
                assertThrows(HttpClientResponseException.class, () -> submit(tenant));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatus());
        assertEquals("1", rejected.getResponse().getHeaders().get("Retry-After"));
    }

    @Test
    void oneTenantsExhaustedBudgetDoesNotRejectAnother() {
        String noisy = TENANTS.get(0);
        String quiet = TENANTS.get(1);

        for (int admitted = 0; admitted < TENANT_BUDGET; admitted++) {
            submit(noisy);
        }
        assertThrows(HttpClientResponseException.class, () -> submit(noisy));

        assertEquals(HttpStatus.ACCEPTED, submit(quiet).getStatus());
    }

    @Test
    void theResourceBudgetCapsTheRouteAcrossTenants() {
        int admitted = 0;
        HttpStatus lastRejection = null;
        for (int attempt = 0; attempt < TENANTS.size() * TENANT_BUDGET; attempt++) {
            try {
                submit(TENANTS.get(attempt % TENANTS.size()));
                admitted++;
            } catch (HttpClientResponseException rejected) {
                lastRejection = rejected.getStatus();
            }
        }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, lastRejection);
        org.junit.jupiter.api.Assertions.assertTrue(admitted <= RESOURCE_BUDGET,
                "route admitted " + admitted + " requests, above its budget of " + RESOURCE_BUDGET);
    }

    @Test
    void aTenantBudgetKeyIdentifiesTheCallerWithoutStoringItsCredential() {
        String tenant = TENANTS.get(2);

        submit(tenant);

        List<String> keys = budgets.sync().keys("rl:*");
        assertEquals(2, keys.stream().filter(key -> key.startsWith("rl:")).count(),
                "expected one resource budget and one tenant budget, got " + keys);
        org.junit.jupiter.api.Assertions.assertTrue(
                keys.stream().anyMatch(key -> key.startsWith("rl:api-tenant-admission:")),
                "no per-tenant budget was created: " + keys);
        org.junit.jupiter.api.Assertions.assertTrue(
                keys.stream().noneMatch(key -> key.contains(tenant)),
                "the raw credential leaked into a budget key: " + keys);
    }

    private HttpResponse<StatusResponse> submit(String apiKey) {
        return client.toBlocking().exchange(
                HttpRequest.POST("/payment-simulations", REQUEST)
                        .header("X-API-Key", apiKey)
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                StatusResponse.class);
    }

    private Map<String, Object> properties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "payment.simulation.wait-timeout", "1s",
                "payment.simulation.rate-limit.limit-for-period", RESOURCE_BUDGET,
                "payment.simulation.rate-limit.tenant-limit-for-period", TENANT_BUDGET,
                "payment.simulation.rate-limit.refresh-period", "1h",
                "payment.security.api-keys", TENANTS);
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
