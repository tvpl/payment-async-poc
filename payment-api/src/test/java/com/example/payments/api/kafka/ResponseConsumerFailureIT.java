package com.example.payments.api.kafka;

import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.Fees;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;
import com.example.payments.common.model.SimulationStatus;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure behaviour of the response consumer against real Kafka/Redis/Apicurio: nothing is
 * acknowledged silently (PAY-09) and a repeated terminal event never rewrites the outcome
 * that was already chosen (PAY-06).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseConsumerFailureIT {

    private static final int CODEC_POOL_SIZE = 3;

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private ApplicationContext context;
    private RedisStatusStore store;
    private AvroSerde serde;
    private KafkaProducer<String, byte[]> producer;
    private KafkaConsumer<String, byte[]> dlqConsumer;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        context = ApplicationContext.run(properties());
        store = context.getBean(RedisStatusStore.class);
        serde = new AvroSerde(registryUrl());
        producer = producer();
        dlqConsumer = dlqConsumer();
        dlqConsumer.subscribe(List.of(Topics.DLQ));
        dlqConsumer.poll(Duration.ofSeconds(2));
        awaitConsumerAssignment();
    }

    @AfterAll
    void stop() {
        dlqConsumer.close();
        producer.close();
        serde.close();
        context.close();
    }

    /**
     * The listener starts at the end of the partition, so a record produced before it is
     * assigned would simply never be seen. Prove it is live before asserting anything.
     */
    private void awaitConsumerAssignment() {
        String probeId = "probe-" + UUID.randomUUID();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            publishCompleted(probeId, "000000");
            assertTrue(store.get(probeId).isPresent(), "response consumer not assigned yet");
        });
    }

    @Test
    void anUnreadableRecordIsDeadLetteredWithItsOriginalBytesInsteadOfSilentlyAcked() {
        String requestId = "poison-" + UUID.randomUUID();
        byte[] garbage = "not-an-avro-record".getBytes(StandardCharsets.UTF_8);

        publishRaw(Topics.COMPLETED, requestId, garbage);

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(requestId);
        assertArrayEquals(garbage, dead.value());
        assertEquals(Topics.COMPLETED, header(dead, "x-dlq-origin-topic"));
        assertEquals("decode", header(dead, "x-dlq-stage"));
        assertNotNull(header(dead, "x-dlq-reason"));
    }

    @Test
    void anUnexpectedEventTypeOnTheFinalTopicIsDeadLettered() {
        String requestId = "wrong-type-" + UUID.randomUUID();
        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1,
                "AUTHORIZE_AND_CAPTURE");
        var envelope = EventEnvelope.create(
                EventTypes.PAYMENT_SIMULATION_REQUESTED, requestId, UUID.randomUUID().toString(),
                requestId, "trace", Sources.API, payload);

        publishRaw(Topics.COMPLETED, requestId,
                serde.serialize(Topics.REQUESTED, AvroMapper.toAvroRequested(envelope)));

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(requestId);
        assertEquals("decode", header(dead, "x-dlq-stage"));
        assertTrue(store.get(requestId).isEmpty(),
                "an unexpected event type must not produce a status");
    }

    @Test
    void anUnreadableRecordDoesNotStopTheNextValidResult() {
        String poisonId = "poison-blocking-" + UUID.randomUUID();
        String validId = "valid-after-poison-" + UUID.randomUUID();

        publishRaw(Topics.COMPLETED, poisonId, "still-not-avro".getBytes(StandardCharsets.UTF_8));
        publishCompleted(validId, "654321");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Optional<StatusEntry> entry = store.get(validId);
            assertTrue(entry.isPresent());
            assertEquals(SimulationStatus.COMPLETED, entry.get().status());
            assertEquals("654321", entry.get().result().authorizationCode());
        });
    }

    @Test
    void aRepeatedIdenticalTerminalEventLeavesTheResultUnchanged() {
        String requestId = "duplicate-" + UUID.randomUUID();

        publishCompleted(requestId, "111111");
        awaitStatus(requestId, SimulationStatus.COMPLETED);
        publishCompleted(requestId, "111111");

        StatusEntry entry = awaitStableEntry(requestId);
        assertEquals(SimulationStatus.COMPLETED, entry.status());
        assertEquals("111111", entry.result().authorizationCode());
    }

    @Test
    void aContradictoryRepeatNeverRewritesTheOutcomeAlreadyChosen() {
        String requestId = "contradictory-" + UUID.randomUUID();

        publishCompleted(requestId, "222222");
        awaitStatus(requestId, SimulationStatus.COMPLETED);
        publishFailed(requestId);

        StatusEntry entry = awaitStableEntry(requestId);
        assertEquals(SimulationStatus.COMPLETED, entry.status());
        assertEquals("222222", entry.result().authorizationCode());
    }

    @Test
    void theResponseCodecIsBoundedByItsConfiguredCapacity() {
        AvroSerde applicationSerde = context.getBean(AvroSerde.class);

        AvroSerde.PoolSnapshot snapshot = applicationSerde.poolSnapshot();

        assertEquals(CODEC_POOL_SIZE, snapshot.capacity());
        assertEquals(CODEC_POOL_SIZE, snapshot.available() + snapshot.borrowed());
    }

    private void awaitStatus(String requestId, SimulationStatus expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Optional<StatusEntry> entry = store.get(requestId);
            assertTrue(entry.isPresent(), "no status for " + requestId);
            assertEquals(expected, entry.get().status());
        });
    }

    /** Gives the repeat time to be consumed, then reads the entry it must not have changed. */
    private StatusEntry awaitStableEntry(String requestId) {
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return store.get(requestId).orElseThrow();
    }

    private ConsumerRecord<String, byte[]> awaitDeadLetter(String key) {
        List<ConsumerRecord<String, byte[]>> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            ConsumerRecords<String, byte[]> polled = dlqConsumer.poll(Duration.ofSeconds(1));
            polled.forEach(seen::add);
            assertTrue(seen.stream().anyMatch(r -> key.equals(r.key())),
                    "no dead letter for " + key);
        });
        return seen.stream().filter(r -> key.equals(r.key())).findFirst().orElseThrow();
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private void publishCompleted(String requestId, String authorizationCode) {
        var result = new SimulationResult(
                UUID.randomUUID().toString(), requestId, SimulationResult.APPROVED, authorizationCode,
                new BigDecimal("125.50"), "BRL", 3,
                new Fees(new BigDecimal("2.49"), new BigDecimal("1.25"), new BigDecimal("122.38")),
                new Settlement(LocalDate.now().plusDays(1), "D+1"), null, null);
        var envelope = EventEnvelope.create(
                EventTypes.PAYMENT_SIMULATION_COMPLETED, requestId, UUID.randomUUID().toString(),
                requestId, "trace", Sources.SBUS, result);
        publishRaw(Topics.COMPLETED, requestId,
                serde.serialize(Topics.COMPLETED, AvroMapper.toAvroCompleted(envelope)));
    }

    private void publishFailed(String requestId) {
        var result = new SimulationResult(
                UUID.randomUUID().toString(), requestId, SimulationResult.DECLINED, null,
                new BigDecimal("125.50"), "BRL", 3, null, null, "DECLINED", "late contradiction");
        var envelope = EventEnvelope.create(
                EventTypes.PAYMENT_SIMULATION_FAILED, requestId, UUID.randomUUID().toString(),
                requestId, "trace", Sources.SBUS, result);
        publishRaw(Topics.FAILED, requestId,
                serde.serialize(Topics.FAILED, AvroMapper.toAvroFailed(envelope)));
    }

    private void publishRaw(String topic, String key, byte[] value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish to " + topic, e);
        }
    }

    private KafkaProducer<String, byte[]> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private KafkaConsumer<String, byte[]> dlqConsumer() {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "dlq-observer-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private Map<String, Object> properties() {
        return Map.of(
                "micronaut.server.enabled", false,
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "payments.avro.codec-pool-size", CODEC_POOL_SIZE,
                "payment.response-consumer.max-attempts", 2,
                "payment.response-consumer.retry-delay", "200ms");
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
