package com.example.payments.sbus.outbox;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.kafka.KafkaPublisher;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.ratelimit.RedisRateLimiter;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.support.Json;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * task_T34 (OBS-02): the outbox publish gets its own span ("outbox publish") linked back to
 * whatever ingestion trace context was persisted on the row, and stamps a fresh, currently-valid
 * {@code traceparent} onto the record it actually sends — never the (possibly stale) one that was
 * merely persisted. Uses a standalone {@link InMemorySpanExporter}-backed {@link Tracer} (not the
 * application's own OTel wiring, which by default exports to a collector that is not running in
 * this test) and a raw {@link KafkaConsumer} to inspect exactly what left the wire, independent of
 * any {@code @KafkaListener}.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxTraceContextIT {

    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

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
    private KafkaPublisher realPublisher;
    private Json json;
    private InMemorySpanExporter spanExporter;
    private Tracer tracer;

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
        realPublisher = context.getBean(KafkaPublisher.class);
        json = context.getBean(Json.class);

        // A standalone SDK, deliberately independent of the application's own OTel wiring (which
        // exports to a collector this test does not run) — captures exactly the spans this
        // dispatcher instance creates, nothing else.
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        tracer = tracerProvider.get("outbox-trace-context-it");
    }

    @BeforeEach
    void cleanState() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM outbox_event");
        }
        spanExporter.reset();
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void publishStampsAFreshValidTraceparentAndLinksBackToTheIngestionContext() throws Exception {
        String ingestionTraceparent = "00-11112222333344445555666677778888-1234567890abcdef-01";
        repository.save(pendingEvent("trace-link", Topics.COMPLETED, ingestionTraceparent));

        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, realPublisher, mock(SbusMetrics.class),
                json, mock(RedisRateLimiter.class), publicationLock, tracer);

        assertEquals(1, dispatcher.dispatchBatch());

        ConsumerRecord<String, byte[]> record = consumeOne(Topics.COMPLETED);
        String outgoingTraceparent = headerValue(record, Headers.TRACEPARENT);
        assertNotNull(outgoingTraceparent, "the published record must carry a traceparent header");
        assertTrue(TRACEPARENT_PATTERN.matcher(outgoingTraceparent).matches(),
                "outgoing traceparent must be a well-formed W3C value: " + outgoingTraceparent);
        assertNotEquals(ingestionTraceparent, outgoingTraceparent,
                "the outgoing traceparent must be the publish span's OWN current context, "
                        + "not a copy of the (possibly stale) ingestion one");

        List<io.opentelemetry.sdk.trace.data.SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        var publishSpan = spans.get(0);
        assertEquals("outbox publish", publishSpan.getName());
        assertEquals(1, publishSpan.getLinks().size(),
                "the publish span must link back to the persisted ingestion context");
        assertEquals("11112222333344445555666677778888",
                publishSpan.getLinks().get(0).getSpanContext().getTraceId(),
                "the link must point at the ingestion trace, not the publish span's own");
        assertTrue(outgoingTraceparent.contains(publishSpan.getSpanContext().getTraceId()),
                "the record's traceparent must be the publish span's own context");
    }

    @Test
    void publishStillStampsAFreshTraceparentWhenNoIngestionContextWasPersisted() throws Exception {
        repository.save(pendingEvent("trace-none", Topics.FAILED, null));

        OutboxDispatcher dispatcher = new OutboxDispatcher(claims, realPublisher, mock(SbusMetrics.class),
                json, mock(RedisRateLimiter.class), publicationLock, tracer);

        assertEquals(1, dispatcher.dispatchBatch());

        ConsumerRecord<String, byte[]> record = consumeOne(Topics.FAILED);
        String outgoingTraceparent = headerValue(record, Headers.TRACEPARENT);
        assertNotNull(outgoingTraceparent);
        assertTrue(TRACEPARENT_PATTERN.matcher(outgoingTraceparent).matches());

        List<io.opentelemetry.sdk.trace.data.SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals("outbox publish", spans.get(0).getName());
        assertEquals(0, spans.get(0).getLinks().size(),
                "no persisted ingestion context means no link to add");
    }

    private static OutboxEvent pendingEvent(String identity, String topic, String traceparent) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("test");
        event.setAggregateId(identity);
        event.setEventType("test");
        event.setTopic(topic);
        event.setKey(identity);
        event.setPayload(new byte[]{1, 2, 3});
        event.setHeaders(traceparent == null ? "{}" : "{\"" + Headers.TRACEPARENT + "\":\"" + traceparent + "\"}");
        event.setStatus(OutboxStatus.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(Instant.now().minusSeconds(1));
        event.setDeduplicationKey(identity);
        return event;
    }

    private static String headerValue(ConsumerRecord<String, byte[]> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private ConsumerRecord<String, byte[]> consumeOne(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-trace-context-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
            throw new AssertionError("No record consumed from " + topic + " within the timeout");
        }
    }

    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
                Map.entry("sbus.outbox.batch-size", 10),
                Map.entry("sbus.outbox.max-attempts", 2),
                Map.entry("sbus.outbox.base-backoff", "10ms"),
                Map.entry("sbus.outbox.max-backoff", "10ms"),
                Map.entry("sbus.outbox.lease", "1m"),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
