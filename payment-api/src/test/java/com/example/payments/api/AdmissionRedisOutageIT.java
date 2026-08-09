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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With Redis down there is no shared window left, so each instance must fall back to its
 * <em>share</em> of the fleet budget. Granting every instance the whole budget would admit
 * {@code instances}× the approved burst precisely during the outage (CAP-03).
 *
 * <p>Redis is stopped for real, which is why this lives apart from the other admission ITs.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdmissionRedisOutageIT {

    private static final String API_KEY = "outage-tenant-key";

    private static final int FLEET_BUDGET = 8;
    private static final int INSTANCES = 4;
    private static final int PER_INSTANCE_SHARE = FLEET_BUDGET / INSTANCES;

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
    void aRedisOutageCapsThisInstanceAtItsShareOfTheFleetBudget() {
        List<HttpStatus> statuses = new ArrayList<>();
        for (int attempt = 0; attempt < FLEET_BUDGET; attempt++) {
            statuses.add(submit());
        }

        List<HttpStatus> admitted = statuses.subList(0, PER_INSTANCE_SHARE);
        List<HttpStatus> rejected = statuses.subList(PER_INSTANCE_SHARE, statuses.size());

        assertFalse(admitted.contains(HttpStatus.TOO_MANY_REQUESTS),
                "the per-instance share must still be admitted during the outage: " + statuses);
        assertTrue(rejected.stream().allMatch(status -> status == HttpStatus.TOO_MANY_REQUESTS),
                "beyond its share the instance must reject, not admit the whole fleet budget: " + statuses);
        assertEquals(FLEET_BUDGET - PER_INSTANCE_SHARE, rejected.size());
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
                "payment.simulation.rate-limit.limit-for-period", FLEET_BUDGET,
                "payment.simulation.rate-limit.tenant-limit-for-period", FLEET_BUDGET,
                "payment.simulation.rate-limit.instances", INSTANCES,
                "payment.simulation.rate-limit.refresh-period", "1h",
                "payment.security.api-keys", List.of(API_KEY));
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
