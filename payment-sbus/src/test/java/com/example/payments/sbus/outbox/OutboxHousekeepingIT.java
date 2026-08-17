package com.example.payments.sbus.outbox;

import com.example.payments.common.events.Topics;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.scheduling.annotation.Scheduled;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task_T16: cohesive hygiene bundle for {@code OutboxHousekeeping} and the claim query index.
 * <ul>
 *   <li>AUD-23: {@code initialDelay} reads a property instead of a hardcoded literal.</li>
 *   <li>AUD-24: the PUBLISHED purge deletes in bounded batches, not the whole backlog at once.</li>
 *   <li>AUD-25: V11 adds the index the PENDING claim query needs.</li>
 * </ul>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxHousekeepingIT {

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
    private OutboxHousekeeping housekeeping;
    private OutboxEventRepository repository;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        housekeeping = context.getBean(OutboxHousekeeping.class);
        repository = context.getBean(OutboxEventRepository.class);
    }

    @BeforeEach
    void cleanDatabase() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM outbox_event");
        }
    }

    @AfterAll
    void stop() {
        context.close();
    }

    /**
     * task_T16 (AUD-23): {@code initialDelay} on the housekeeping job's {@code @Scheduled} now
     * resolves from {@code sbus.outbox.housekeeping-initial-delay} — a property placeholder, not
     * the previous hardcoded {@code "1h"} literal. This context booting successfully (in
     * {@link #start()}) already proves the placeholder resolves to a valid duration at runtime;
     * this asserts directly that it is a property, not a literal.
     */
    @Test
    void initialDelayIsConfigurableNotHardcoded() throws Exception {
        Scheduled scheduled = OutboxHousekeeping.class.getMethod("purge").getAnnotation(Scheduled.class);

        assertNotEquals("1h", scheduled.initialDelay(),
                "initialDelay must no longer be the bare hardcoded literal");
        assertTrue(scheduled.initialDelay().contains("sbus.outbox.housekeeping-initial-delay"),
                "initialDelay must resolve from a configurable property: " + scheduled.initialDelay());
    }

    /**
     * task_T16 (AUD-24): each individual DELETE is still bounded to one batch's worth (size 2,
     * see {@link #properties()}) — never one unbounded statement over the whole backlog.
     * RES-02 (T28): a single {@code purge()} call now loops batch after batch until the backlog
     * is fully drained (or the time cap is hit), instead of purging at most one batch per
     * scheduled interval regardless of how large the backlog is — five stale rows across three
     * batches of at most two are all gone after one call.
     */
    @Test
    void purgeDrainsTheWholeBacklogInOneRunAcrossSeveralBoundedBatches() throws Exception {
        for (int i = 0; i < 5; i++) {
            repository.save(publishedEvent("stale-" + i));
        }
        assertEquals(5, countRows());

        housekeeping.purge();

        assertEquals(0, countRows(), "one run must drain the whole eligible backlog, not just one batch");
    }

    /**
     * RES-02: a run that hits its time cap before the backlog drains must stop instead of
     * running unbounded. A cap of {@code 0ms} makes the very first elapsed-time check (right
     * after the first batch's DELETE round-trip) already past the deadline, so exactly one batch
     * is purged and the rest of a ten-batch backlog is left for the next run.
     */
    @Test
    void purgeStopsAtTheTimeCapInsteadOfDrainingAnArbitrarilyLargeBacklog() throws Exception {
        for (int i = 0; i < 20; i++) {
            repository.save(publishedEvent("capped-" + i));
        }
        assertEquals(20, countRows());

        try (var capped = ApplicationContext.run(cappedProperties())) {
            OutboxHousekeeping cappedHousekeeping = capped.getBean(OutboxHousekeeping.class);
            cappedHousekeeping.purge();
        }

        assertEquals(18, countRows(),
                "a 0ms time cap must stop the loop after exactly one batch (size 2), leaving the rest for the next run");
    }

    /** task_T16 (AUD-25): V11 creates the index the PENDING claim query needs. */
    @Test
    void v11CreatesTheIndexForThePendingClaimQuery() throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement(
                     "SELECT indexdef FROM pg_indexes WHERE tablename = 'outbox_event' AND indexname = ?")) {
            statement.setString(1, "idx_outbox_event_pending");
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "idx_outbox_event_pending must exist after V11");
                String definition = result.getString(1).toLowerCase();
                assertTrue(definition.contains("next_attempt_at") && definition.contains("created_at"),
                        "index must cover the claim query's WHERE + ORDER BY columns: " + definition);
                assertTrue(definition.contains("pending"),
                        "index must be scoped to the PENDING status (partial index): " + definition);
            }
        }
    }

    private static OutboxEvent publishedEvent(String identity) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("test");
        event.setAggregateId(identity);
        event.setEventType("test");
        event.setTopic(Topics.COMPLETED);
        event.setKey(identity);
        event.setPayload(new byte[]{1});
        event.setHeaders("{}");
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setAttempts(0);
        event.setNextAttemptAt(Instant.now());
        event.setPublishedAt(Instant.now().minusSeconds(10 * 24 * 3600));
        event.setDeduplicationKey(identity);
        return event;
    }

    private int countRows() throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement("SELECT count(*) FROM outbox_event")) {
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }

    /** T28 (RES-02): same fixture as {@link #properties()}, but with the time cap forced to 0. */
    private static Map<String, Object> cappedProperties() {
        Map<String, Object> base = new java.util.HashMap<>(properties());
        base.put("sbus.outbox.housekeeping-time-cap", "0ms");
        return base;
    }

    private static Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("apicurio.registry.url", registryUrl()),
                Map.entry("redis.uri", REDIS.getRedisURI()),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl() + "?stringtype=unspecified"),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("sbus.outbox.batch-size", 2),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("sbus.outbox.housekeeping-interval", "1h"),
                Map.entry("sbus.outbox.housekeeping-initial-delay", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
