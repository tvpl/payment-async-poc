package com.example.payments.sbus.kafka;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task_T39 (SCAL-03): with the {@code payment-sbus-requested} listener configured for 3 threads
 * (see {@code sbus.kafka.consumers.requested.threads} on {@link PaymentRequestedConsumer}) and the
 * topic pre-created with 3 partitions, three distinct-key messages — one per partition — must
 * process concurrently, not serialized one at a time behind a single consumer thread.
 *
 * <p>Proven by self-calibrated timing rather than a fixed threshold: first measures how long ONE
 * message alone takes end to end (publish -&gt; its row visible), THEN measures three messages
 * published back to back on three different partitions. Serialized, three would take roughly 3x
 * the single-message baseline; concurrent, roughly 1x — a generous margin between those two
 * absorbs jitter while still clearly distinguishing the two.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentRequestedConsumerConcurrencyIT {

    private static final int PARTITIONS = 3;

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
    private AvroSerde serde;
    private PaymentSbusMessageRepository messages;
    private KafkaProducer<String, byte[]> producer;

    @BeforeAll
    void start() throws Exception {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();

        // Pre-create with 3 partitions BEFORE the app's own listener ever subscribes — auto
        // topic creation would otherwise default to a single partition, making every message
        // land on the same partition regardless of key and defeating the whole point.
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            admin.createTopics(List.of(new NewTopic(Topics.REQUESTED, PARTITIONS, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }

        serde = new AvroSerde(registryUrl());
        context = ApplicationContext.run(properties());
        messages = context.getBean(PaymentSbusMessageRepository.class);
        producer = producer();
    }

    @AfterAll
    void stop() {
        producer.close();
        context.close();
    }

    @Test
    void withThreeThreadsThreeDistinctKeyedMessagesOnThreePartitionsProcessConcurrently() throws Exception {
        // Warm-up: JIT/connection-pool/schema-registration costs on the FIRST message ever
        // processed in this context would otherwise pollute the baseline measurement below.
        publishAndAwait(0);

        long baselineStart = System.nanoTime();
        publishAndAwait(1);
        Duration baseline = Duration.ofNanos(System.nanoTime() - baselineStart);

        String[] requestIds = new String[PARTITIONS];
        long batchStart = System.nanoTime();
        for (int partition = 0; partition < PARTITIONS; partition++) {
            requestIds[partition] = publish(partition);
        }
        for (String requestId : requestIds) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertTrue(messages.findByRequestId(requestId).isPresent()));
        }
        Duration batchElapsed = Duration.ofNanos(System.nanoTime() - batchStart);

        assertTrue(batchElapsed.compareTo(baseline.multipliedBy(5).dividedBy(2)) < 0,
                "three distinct-key messages across three partitions took " + batchElapsed
                        + " against a single-message baseline of " + baseline
                        + " — looks serialized, not concurrent under threads=" + PARTITIONS);
    }

    private void publishAndAwait(int partition) throws Exception {
        String requestId = publish(partition);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertTrue(messages.findByRequestId(requestId).isPresent()));
    }

    private String publish(int partition) throws Exception {
        String requestId = UUID.randomUUID().toString();
        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
        var env = EventEnvelope.create(EventTypes.PAYMENT_SIMULATION_REQUESTED,
                requestId, UUID.randomUUID().toString(), requestId, "trace-" + requestId, Sources.API, payload);
        byte[] bytes = serde.serialize(Topics.REQUESTED, AvroMapper.toAvroRequested(env));
        // Explicit partition assignment (not key-hash based) makes partition placement exact and
        // deterministic, instead of trusting the default partitioner to spread 3 arbitrary keys.
        producer.send(new ProducerRecord<>(Topics.REQUESTED, partition, requestId, bytes)).get();
        return requestId;
    }

    private static Properties adminProperties() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        return props;
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
                Map.entry("sbus.kafka.consumers.requested.threads", PARTITIONS),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
