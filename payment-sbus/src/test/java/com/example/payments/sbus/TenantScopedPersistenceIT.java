package com.example.payments.sbus;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.sbus.domain.PaymentSbusMessage;
import com.example.payments.sbus.domain.SbusMessageStatus;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import com.example.payments.sbus.service.PaymentSimulationService;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * T11/TEN-06: {@code PaymentSimulationService}/{@code PaymentPersistenceService} resolve replay
 * and persist state scoped by {@code (tenantId, idempotencyKey)}, reading {@code tenantId} straight
 * off the inbound envelope (TEN-05 — the Edge already stamped it there). Two distinct tenants
 * reusing the exact same idempotency key AND payload must never be treated as a replay of one
 * another (that would leak one tenant's result/requestId onto another's request — the crash this
 * feature exists to close). An envelope with no tenant identity at all falls back to the synthetic
 * {@code legacy} tenant instead of erroring.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantScopedPersistenceIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    private ApplicationContext context;
    private PaymentSimulationService service;
    private PaymentSbusMessageRepository messages;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        service = context.getBean(PaymentSimulationService.class);
        messages = context.getBean(PaymentSbusMessageRepository.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void twoTenantsReusingTheSameIdempotencyKeyAndPayloadProcessAsIndependentSimulationsNeverAsAReplay() {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        String tenantARequestId = UUID.randomUUID().toString();
        String tenantBRequestId = UUID.randomUUID().toString();

        var tenantAEnv = requestedEnvelope(tenantARequestId, "tenant-a");
        service.handleRequested(tenantAEnv, idempotencyKey, null);
        var tenantBEnv = requestedEnvelope(tenantBRequestId, "tenant-b");
        service.handleRequested(tenantBEnv, idempotencyKey, null);

        PaymentSbusMessage tenantARow = messages.findByRequestId(tenantARequestId).orElseThrow();
        PaymentSbusMessage tenantBRow = messages.findByRequestId(tenantBRequestId).orElseThrow(
                () -> new AssertionError("tenant-b's request was dropped as if it were a replay of tenant-a's"));

        assertEquals("tenant-a", tenantARow.getTenantId());
        assertEquals("tenant-b", tenantBRow.getTenantId());
        assertNotEquals(tenantARow.getSimulationId(), tenantBRow.getSimulationId(),
                "each tenant must get its own simulation — sharing one would leak tenant-a's "
                        + "requestId/result onto tenant-b's request");
        assertEquals(SbusMessageStatus.PROCESSING, tenantBRow.getStatus(),
                "tenant-b's own request must go through its own Core round-trip, not be resolved "
                        + "as a replay carrying tenant-a's outcome");
    }

    @Test
    void anEnvelopeWithNoTenantIdentityFallsBackToTheLegacyTenantWithoutError() {
        String requestId = UUID.randomUUID().toString();
        var env = requestedEnvelope(requestId, "");

        service.handleRequested(env, null, null);

        PaymentSbusMessage row = messages.findByRequestId(requestId).orElseThrow();
        assertEquals("legacy", row.getTenantId());
        assertEquals(SbusMessageStatus.PROCESSING, row.getStatus());
    }

    private static EventEnvelope<PaymentSimulationRequestPayload> requestedEnvelope(
            String requestId, String tenantId) {
        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("50.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
        return EventEnvelope.create(EventTypes.PAYMENT_SIMULATION_REQUESTED,
                requestId, UUID.randomUUID().toString(), requestId, "trace-" + requestId, Sources.API,
                tenantId, payload);
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }

    private static Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("apicurio.registry.url", registryUrl()),
                Map.entry("redis.uri", REDIS.getRedisURI()),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl() + "?stringtype=unspecified"),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
