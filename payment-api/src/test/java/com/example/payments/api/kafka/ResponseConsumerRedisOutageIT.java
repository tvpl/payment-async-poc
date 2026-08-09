package com.example.payments.api.kafka;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.Fees;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;
import com.example.payments.api.redis.RedisStatusStore;
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
import java.util.Properties;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Redis outage must not swallow a final result: the record is retried within its budget and
 * then dead-lettered with the original bytes, so it stays recoverable and observable (PAY-09).
 *
 * <p>Redis is stopped for real, which is why this lives apart from the other consumer ITs.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseConsumerRedisOutageIT {

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private ApplicationContext context;
    private AvroSerde serde;
    private KafkaProducer<String, byte[]> producer;
    private KafkaConsumer<String, byte[]> dlqConsumer;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        context = ApplicationContext.run(properties());
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

    private void awaitConsumerAssignment() {
        RedisStatusStore store = context.getBean(RedisStatusStore.class);
        String probeId = "probe-" + UUID.randomUUID();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            publishCompleted(probeId);
            assertTrue(store.get(probeId).isPresent(), "response consumer not assigned yet");
        });
    }

    @Test
    void aRedisOutageDeadLettersTheResultInsteadOfDroppingIt() {
        String requestId = "redis-outage-" + UUID.randomUUID();
        byte[] payload = completedBytes(requestId);

        REDIS.stop();
        publishRaw(Topics.COMPLETED, requestId, payload);

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(requestId);
        assertEquals("apply", header(dead, "x-dlq-stage"));
        assertEquals(Topics.COMPLETED, header(dead, "x-dlq-origin-topic"));
        assertArrayEquals(payload, dead.value(),
                "the dead letter must carry the original result, not a placeholder");
    }

    private ConsumerRecord<String, byte[]> awaitDeadLetter(String key) {
        List<ConsumerRecord<String, byte[]>> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            ConsumerRecords<String, byte[]> polled = dlqConsumer.poll(Duration.ofSeconds(1));
            polled.forEach(seen::add);
            assertTrue(seen.stream().anyMatch(r -> key.equals(r.key())), "no dead letter for " + key);
        });
        return seen.stream().filter(r -> key.equals(r.key())).findFirst().orElseThrow();
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private byte[] completedBytes(String requestId) {
        var result = new SimulationResult(
                UUID.randomUUID().toString(), requestId, SimulationResult.APPROVED, "999999",
                new BigDecimal("125.50"), "BRL", 3,
                new Fees(new BigDecimal("2.49"), new BigDecimal("1.25"), new BigDecimal("122.38")),
                new Settlement(LocalDate.now().plusDays(1), "D+1"), null, null);
        var envelope = EventEnvelope.create(
                EventTypes.PAYMENT_SIMULATION_COMPLETED, requestId, UUID.randomUUID().toString(),
                requestId, "trace", Sources.SBUS, result);
        return serde.serialize(Topics.COMPLETED, AvroMapper.toAvroCompleted(envelope));
    }

    private void publishCompleted(String requestId) {
        publishRaw(Topics.COMPLETED, requestId, completedBytes(requestId));
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
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "payment.response-consumer.max-attempts", 2,
                "payment.response-consumer.retry-delay", "200ms");
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
