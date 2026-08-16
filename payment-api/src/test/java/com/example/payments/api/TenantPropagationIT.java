package com.example.payments.api;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusResponse;
import com.example.payments.common.avro.PaymentSimulationRequested;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * TEN-05: the tenant resolved from the API key's binding reaches both the Avro envelope
 * ({@code tenantId}) and the Kafka header ({@code x-tenant-id}) on {@code PaymentSimulationRequested}.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantPropagationIT {

    private static final String API_KEY = "tenant-propagation-key";
    private static final String TENANT = "tenant-propagation";
    private static final String TEST_JWT_SECRET = "test-only-api-signing-secret-with-at-least-32-bytes";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private EmbeddedServer server;
    private HttpClient client;
    private AvroSerde serde;
    private KafkaConsumer<String, byte[]> consumer;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        server = ApplicationContext.run(EmbeddedServer.class, properties());
        client = HttpClient.create(server.getURL());
        serde = new AvroSerde(registryUrl());
        consumer = consumer();
        consumer.subscribe(List.of(Topics.REQUESTED));
    }

    @AfterAll
    void stop() {
        consumer.close();
        serde.close();
        client.close();
        server.close();
    }

    @Test
    void envelopeAndKafkaHeaderCarryTheTenantResolvedFromTheBinding() {
        var request = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
        String idempotencyKey = UUID.randomUUID().toString();

        HttpResponse<StatusResponse> accepted = client.toBlocking().exchange(
                HttpRequest.POST("/payment-simulations", request)
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", idempotencyKey),
                StatusResponse.class);
        String requestId = accepted.body().requestId();

        ConsumerRecord<String, byte[]> record = awaitRecord(requestId);

        PaymentSimulationRequested avro = serde.deserialize(Topics.REQUESTED, record.value());
        EventEnvelope<?> envelope = AvroMapper.fromAvro(avro);
        assertEquals(TENANT, envelope.tenantId());
        assertEquals(TENANT, headerValue(record, Headers.TENANT_ID));
    }

    private ConsumerRecord<String, byte[]> awaitRecord(String requestId) {
        var found = new java.util.concurrent.atomic.AtomicReference<ConsumerRecord<String, byte[]>>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> record : records) {
                if (requestId.equals(record.key())) {
                    found.set(record);
                }
            }
            assertNotNull(found.get(), "no PaymentSimulationRequested record seen yet for " + requestId);
        });
        return found.get();
    }

    private static String headerValue(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertNotNull(header, "missing Kafka header " + name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private KafkaConsumer<String, byte[]> consumer() {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "tenant-propagation-observer-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private static Map<String, Object> properties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "payment.simulation.wait-timeout", "1s",
                "payment.security.api-keys", List.of(API_KEY),
                "payment.security.tenants." + hash(API_KEY), List.of(TENANT),
                "micronaut.security.token.jwt.signatures.secret.generator.secret", TEST_JWT_SECRET,
                "micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256");
    }

    private static String hash(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
