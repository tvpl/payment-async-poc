package com.example.payments.sbus.retry;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.outbox.OutboxClaimService;
import com.example.payments.sbus.support.Json;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DurableRetryIT {

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
    private DurableRetryScheduler scheduler;
    private OutboxClaimService claims;
    private Json json;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        scheduler = context.getBean(DurableRetryScheduler.class);
        claims = context.getBean(OutboxClaimService.class);
        json = context.getBean(Json.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void futureRetryIsPersistedButNeverClaimedEarly() throws Exception {
        ConsumerRecord<String, byte[]> source = record(100, new byte[]{1, 2});
        var scheduled = scheduler.schedule(Topics.REQUESTED, source,
                Map.of(Headers.TRACEPARENT, "00-trace"), 1, new RuntimeException("db"));

        assertTrue(scheduled.inserted());
        assertEquals(1, countByDeduplicationKey(scheduled.deduplicationKey()));
        assertTrue(scheduled.dueAt().isAfter(Instant.now()));
        assertFalse(claims.claimBatch().stream()
                .anyMatch(row -> scheduled.deduplicationKey().equals(row.getDeduplicationKey())));
    }

    @Test
    void dueRetryClaimPreservesRawBytesKeyAndHeaders() throws Exception {
        byte[] raw = new byte[]{3, 4, 5};
        ConsumerRecord<String, byte[]> source = record(101, raw);
        var scheduled = scheduler.schedule(Topics.REQUESTED, source,
                Map.of(Headers.TRACEPARENT, "00-preserved"), 1, new RuntimeException("timeout"));
        makeDue(scheduled.deduplicationKey());

        OutboxEvent claimed = claims.claimBatch().stream()
                .filter(row -> scheduled.deduplicationKey().equals(row.getDeduplicationKey()))
                .findFirst().orElseThrow();

        assertEquals(source.key(), claimed.getKey());
        assertEquals(Topics.REQUESTED_RETRY, claimed.getTopic());
        assertArrayEquals(raw, claimed.getPayload());
        Map<?, ?> persistedHeaders = json.fromJson(claimed.getHeaders(), Map.class);
        assertEquals("00-preserved", persistedHeaders.get(Headers.TRACEPARENT));
        assertEquals("1", persistedHeaders.get(Headers.RETRY_ATTEMPT));
    }

    @Test
    void crashRedeliveryReusesDurableScheduleIdentity() throws Exception {
        ConsumerRecord<String, byte[]> source = record(102, new byte[]{6});

        var first = scheduler.schedule(Topics.REQUESTED, source, Map.of(), 1,
                new RuntimeException("before-offset-commit"));
        var redelivery = scheduler.schedule(Topics.REQUESTED, source, Map.of(), 1,
                new RuntimeException("after-restart"));

        assertTrue(first.inserted());
        assertFalse(redelivery.inserted());
        assertEquals(first.deduplicationKey(), redelivery.deduplicationKey());
        assertEquals(1, countByDeduplicationKey(first.deduplicationKey()));
    }

    @Test
    void dueTrafficIsClaimedWhileFutureRetryRemainsPending() throws Exception {
        var future = scheduler.schedule(Topics.REQUESTED, record(103, new byte[]{7}),
                Map.of(), 1, new RuntimeException("future"));
        var due = scheduler.schedule(Topics.REQUESTED, record(104, new byte[]{8}),
                Map.of(), 1, new RuntimeException("due"));
        makeDue(due.deduplicationKey());

        List<OutboxEvent> claimed = claims.claimBatch();

        assertTrue(claimed.stream().anyMatch(row -> due.deduplicationKey().equals(row.getDeduplicationKey())));
        assertFalse(claimed.stream().anyMatch(row -> future.deduplicationKey().equals(row.getDeduplicationKey())));
        assertEquals("PENDING", statusOf(future.deduplicationKey()));
    }

    @Test
    void retryConsumerContainsNoPartitionSleep() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/payments/sbus/kafka/RetryConsumer.java"));

        assertFalse(source.contains("Thread.sleep"));
        assertFalse(source.contains("waitUntilNotBefore"));
    }

    private static ConsumerRecord<String, byte[]> record(long offset, byte[] value) {
        return new ConsumerRecord<>(Topics.REQUESTED, 0, offset,
                "request-" + offset, value);
    }

    private static int countByDeduplicationKey(String key) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM outbox_event WHERE deduplication_key = ?")) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void makeDue(String key) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement(
                     "UPDATE outbox_event SET next_attempt_at = now() - interval '1 second' "
                             + "WHERE deduplication_key = ?")) {
            statement.setString(1, key);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String statusOf(String key) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement(
                     "SELECT status FROM outbox_event WHERE deduplication_key = ?")) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("apicurio.registry.url", registryUrl()),
                Map.entry("redis.uri", REDIS.getRedisURI()),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl() + "?stringtype=unspecified"),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("sbus.retry.base-delay", "30s"),
                Map.entry("sbus.retry.max-delay", "30s"),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080)
                + "/apis/registry/v2";
    }
}
