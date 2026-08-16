package com.example.payments.sbus.kafka;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.sbus.support.Json;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IDEM-04: an {@code Idempotency-Key} header longer than the {@code VARCHAR(128)} column that
 * validation at the Edge would normally reject (T5) is injected DIRECTLY on the Kafka topic here —
 * simulating a caller that bypasses the Edge (a misbehaving producer, a bug in another service) —
 * and must still be caught by the SBUS's own defense in depth: the resulting Postgres
 * data-integrity violation (SQLState 22001, string right truncation) is classified poison and
 * routed straight to the DLQ, never through the transient-retry path.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConstraintViolationPoisonIT {

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
    private Json json;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        json = context.getBean(Json.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void anOversizedIdempotencyKeyInjectedViaKafkaGoesStraightToTheDlqWithoutRetrying() throws Exception {
        AvroSerde serde = new AvroSerde(registryUrl());
        String requestId = UUID.randomUUID().toString();
        String oversizedKey = "k".repeat(200);

        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("50.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
        var requested = EventEnvelope.create(EventTypes.PAYMENT_SIMULATION_REQUESTED,
                requestId, UUID.randomUUID().toString(), requestId, "trace", Sources.API, payload);

        try (KafkaProducer<String, byte[]> producer = producer()) {
            byte[] bytes = serde.serialize(Topics.REQUESTED, AvroMapper.toAvroRequested(requested));
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(Topics.REQUESTED, requestId, bytes);
            record.headers().add(new RecordHeader(Headers.IDEMPOTENCY_KEY, oversizedKey.getBytes(StandardCharsets.UTF_8)));
            producer.send(record).get();
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            OutboxRow row = dlqRow(requestId);
            assertEquals(Topics.DLQ, row.topic(), "the poison message must land on the DLQ topic, "
                    + "never a retry topic");
            assertEquals("poison", row.dlqStage(), "must be classified poison directly — "
                    + "\"retries-exhausted\" would mean it went through the transient-retry path first");
            assertTrue(row.dlqReason().contains("SQLState 22"),
                    "the sanitized reason must carry the SQLState, got: " + row.dlqReason());
            assertFalse(row.dlqReason().contains(oversizedKey.substring(0, 50)),
                    "the sanitized reason must never embed the offending value itself");
            assertFalse(row.dlqReason().contains("MERCHANT-001"),
                    "the sanitized reason must never embed the business payload (SEC-02/SEC-03), "
                            + "unlike the raw driver message (Postgres's \"Detail: Failing row contains\")");
        });

        // Exactly one outbox row for this message — no separate retry-topic row was ever written
        // alongside the DLQ one, proving the poison path skipped transient retry entirely.
        assertEquals(1, countOutboxRowsForKey(requestId));
    }

    private OutboxRow dlqRow(String messageKey) throws Exception {
        try (Connection connection = connection();
             var statement = connection.prepareStatement(
                     "SELECT topic, headers::text FROM outbox_event WHERE message_key = ?")) {
            statement.setString(1, messageKey);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "no outbox row was written yet for this message");
                String topic = result.getString(1);
                Map<?, ?> headers = json.fromJson(result.getString(2), Map.class);
                return new OutboxRow(topic, String.valueOf(headers.get("x-dlq-stage")),
                        String.valueOf(headers.get("x-dlq-reason")));
            }
        }
    }

    private static int countOutboxRowsForKey(String messageKey) throws Exception {
        try (Connection connection = connection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM outbox_event WHERE message_key = ?")) {
            statement.setString(1, messageKey);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private record OutboxRow(String topic, String dlqStage, String dlqReason) {
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private KafkaProducer<String, byte[]> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(props);
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
