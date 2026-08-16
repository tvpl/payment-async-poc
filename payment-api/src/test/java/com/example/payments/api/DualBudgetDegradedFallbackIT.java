package com.example.payments.api;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusResponse;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task_T5 (AUD-05): {@link com.example.payments.api.ratelimit.RedisRateLimiter#tryAcquireBoth}
 * degrades (Redis unreachable) to independent local per-instance shares of <strong>both</strong>
 * budgets, not just the route one. {@link AdmissionRedisOutageIT} already proves the route share
 * applies, but it configures the route and tenant budgets identically, so it cannot tell which
 * of the two checks is actually doing the denying. This test makes the route share deliberately
 * generous and the tenant share the only real constraint, so a pass here can only mean the
 * tenant budget's own local fallback is still being evaluated.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DualBudgetDegradedFallbackIT {

    private static final String API_KEY = "degraded-dual-budget-tenant-key";

    private static final int ROUTE_BUDGET = 20;
    private static final int INSTANCES = 3;
    private static final int TENANT_BUDGET = 6;
    private static final int TENANT_PER_INSTANCE_SHARE = TENANT_BUDGET / INSTANCES;

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

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        server = ApplicationContext.run(EmbeddedServer.class, properties());
        client = HttpClient.create(server.getURL());
        REDIS.stop();
    }

    @AfterAll
    void stop() {
        client.close();
        server.close();
    }

    @Test
    void aRedisOutageStillCapsTheTenantAtItsOwnShareEvenWhenTheRouteShareIsGenerous() {
        // Redis is fully down, so the durable status store also fails closed for every
        // request (a separate, already-covered concern - RedisOutageFailClosedIT); the signal
        // this test cares about is admission only, so "admitted" here means "not rejected by
        // the limiter (429)", the same convention AdmissionRedisOutageIT uses.
        List<HttpStatus> statuses = new ArrayList<>();
        for (int attempt = 0; attempt < TENANT_BUDGET; attempt++) {
            statuses.add(submit());
        }

        List<HttpStatus> admitted = statuses.subList(0, TENANT_PER_INSTANCE_SHARE);
        List<HttpStatus> rejected = statuses.subList(TENANT_PER_INSTANCE_SHARE, statuses.size());

        assertFalse(admitted.contains(HttpStatus.TOO_MANY_REQUESTS),
                "the tenant's own local share must still be let through the limiter, even though the "
                        + "route budget's local share (" + (ROUTE_BUDGET / INSTANCES)
                        + ") is generous enough to never deny on its own: " + statuses);
        assertTrue(rejected.stream().allMatch(status -> status == HttpStatus.TOO_MANY_REQUESTS),
                "beyond the tenant's own local share of " + TENANT_PER_INSTANCE_SHARE
                        + " the limiter must reject with 429, not silently admit past it: " + statuses);
    }

    private HttpStatus submit() {
        try {
            return client.toBlocking().exchange(
                    HttpRequest.POST("/payment-simulations", REQUEST)
                            .header("X-API-Key", API_KEY)
                            .header("Idempotency-Key", UUID.randomUUID().toString()),
                    StatusResponse.class).getStatus();
        } catch (HttpClientResponseException rejected) {
            return rejected.getStatus();
        }
    }

    private Map<String, Object> properties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "payment.simulation.wait-timeout", "1s",
                "payment.simulation.rate-limit.limit-for-period", ROUTE_BUDGET,
                "payment.simulation.rate-limit.tenant-limit-for-period", TENANT_BUDGET,
                "payment.simulation.rate-limit.instances", INSTANCES,
                "payment.simulation.rate-limit.refresh-period", "1h",
                "payment.security.api-keys", List.of(API_KEY));
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
