package com.example.payments.sbus.outbox;

import com.example.payments.common.events.Topics;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.kafka.KafkaPublisher;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.ratelimit.RedisRateLimiter;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.support.Json;
import com.redis.testcontainers.RedisContainer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * task_T41 (SCAL-04): {@link OutboxPublicationLock} used to borrow a connection from the pool
 * ({@code dataSource.getConnection()}) and never close it — every {@code executeIfAcquired} call
 * leaked one Hikari connection. With a small pool ({@code maximum-pool-size}) and a batch well
 * past that size, the pre-fix code would exhaust the pool partway through and every subsequent
 * borrow would time out; the fixed code (try-with-resources) returns every connection immediately
 * after its own use, so the pool's own {@code active} gauge (already exposed via Micrometer —
 * {@code HikariPoolHealthIndicator}, task_T30) is back at its pre-batch baseline once the whole
 * batch finishes, regardless of how many rows went through.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxPublicationLockConnectionLeakIT {

    private static final int PUBLICATION_COUNT = 50;
    private static final int POOL_SIZE = 10;

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
    private OutboxClaimService claims;
    private OutboxEventRepository repository;
    private OutboxPublicationLock publicationLock;
    private Json json;
    private Tracer tracer;
    private MeterRegistry meterRegistry;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        claims = context.getBean(OutboxClaimService.class);
        repository = context.getBean(OutboxEventRepository.class);
        publicationLock = context.getBean(OutboxPublicationLock.class);
        json = context.getBean(Json.class);
        tracer = context.getBean(Tracer.class);
        meterRegistry = context.getBean(MeterRegistry.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void fiftyPublicationsLeaveActiveConnectionsBackAtTheBaseline() throws Exception {
        // Baseline measured after everything has settled, not right at startup (migrations/health
        // checks may still be returning connections at that instant).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(0.0, activeConnections(), 0.001));
        double baseline = activeConnections();

        for (int i = 0; i < PUBLICATION_COUNT; i++) {
            repository.save(pendingEvent("leak-check-" + i, Topics.REQUESTED));
        }
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, publisher, mock(SbusMetrics.class),
                json, mock(RedisRateLimiter.class), publicationLock, tracer);

        int published = dispatcher.dispatchBatch();

        assertEquals(PUBLICATION_COUNT, published,
                "all " + PUBLICATION_COUNT + " rows must have published successfully — a pool "
                        + "exhausted by a connection leak would fail some of them instead");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(baseline, activeConnections(), 0.001,
                        "active connections must be back at baseline after " + PUBLICATION_COUNT
                                + " publications through a pool of only " + POOL_SIZE
                                + " — a leak would leave them all checked out"));
    }

    private double activeConnections() {
        return meterRegistry.find("hikaricp.connections.active").gauge().value();
    }

    private static OutboxEvent pendingEvent(String identity, String topic) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("test");
        event.setAggregateId(identity);
        event.setEventType("test");
        event.setTopic(topic);
        event.setKey(identity);
        event.setPayload(new byte[]{1});
        event.setHeaders("{}");
        event.setStatus(OutboxStatus.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(Instant.now().minusSeconds(1));
        event.setDeduplicationKey(identity);
        return event;
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
                // Deliberately small and bounded: a leak must be caught by a fast timeout, not a
                // hang, since the pre-fix code would otherwise exhaust this well before 50 calls.
                Map.entry("datasources.default.maximum-pool-size", POOL_SIZE),
                Map.entry("datasources.default.connection-timeout", 3000),
                Map.entry("sbus.outbox.batch-size", PUBLICATION_COUNT + 10),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
