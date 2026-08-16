package com.example.payments.api.filter;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUDG-03/BUDG-04 behavioral proof: {@code ConcurrencyLimitFilter}'s synchronous Redis round-trip
 * (the dual-budget Lua eval) must never execute on the Netty event loop, since that is exactly
 * what would let a degraded Redis stall {@code /health/liveness} alongside every other connection
 * the event loop serves. {@link ThreadCapturingRedisCommandsProvider} substitutes the real Redis
 * commands provider and records whether each call ran on a virtual thread - Netty's event loop
 * threads are always platform threads, so an all-virtual result is direct proof the migration to
 * {@code @ExecuteOn(TaskExecutors.BLOCKING)} took effect at runtime, not just in source.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyLimitFilterOffEventLoopIT {

    private static final String API_KEY = "off-event-loop-tenant-key";

    private static final PaymentSimulationRequest REQUEST = new PaymentSimulationRequest(
            "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");

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
    void theAdmissionRedisCallNeverRunsOnTheNettyEventLoop() {
        ThreadCapturingRedisCommandsProvider.VIRTUAL_THREAD_FLAGS.clear();

        try {
            client.toBlocking().exchange(HttpRequest.POST("/payment-simulations", REQUEST)
                    .header("X-API-Key", API_KEY)
                    .header("Idempotency-Key", UUID.randomUUID().toString()));
        } catch (HttpClientResponseException ignored) {
            // Only the admission Redis call matters here; whatever the request eventually
            // resolves to (accepted, timed out waiting for a simulator that never answers) is
            // irrelevant to this test.
        }

        List<Boolean> ranOnVirtualThread = new ArrayList<>();
        ThreadCapturingRedisCommandsProvider.VIRTUAL_THREAD_FLAGS.drainTo(ranOnVirtualThread);

        assertFalse(ranOnVirtualThread.isEmpty(),
                "expected ConcurrencyLimitFilter to call Redis at least once for this request");
        assertTrue(ranOnVirtualThread.stream().allMatch(Boolean::booleanValue),
                "the admission Redis call ran on a non-virtual thread - i.e. the Netty event loop, "
                        + "not TaskExecutors.BLOCKING");
    }

    private Map<String, Object> properties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("kafka.bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("redis.uri", REDIS.getRedisURI());
        properties.put("apicurio.registry.url", registryUrl());
        properties.put("otel.traces.exporter", "none");
        properties.put("payment.simulation.wait-timeout", "1s");
        properties.put("payment.security.api-keys", List.of(API_KEY));
        properties.put("test.capture-redis-thread", "true");
        return properties;
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
