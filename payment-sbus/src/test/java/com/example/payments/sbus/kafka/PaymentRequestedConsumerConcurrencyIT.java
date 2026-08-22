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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task_T39 (SCAL-03): with the {@code payment-sbus-requested} listener configured for 3 threads
 * (see {@code sbus.kafka.consumers.requested.threads} on {@link PaymentRequestedConsumer}) and the
 * topic pre-created with 3 partitions, three distinct-key messages — one per partition — must
 * process concurrently, not serialized one at a time behind a single consumer thread.
 *
 * <p>Proven by directly observing thread overlap, not by comparing wall-clock durations: a
 * background sampler thread repeatedly dumps all live threads while the batch is in flight and
 * counts how many are, at that sampled instant, inside {@link PaymentRequestedConsumer#receive}.
 * Serialized processing can never show more than one thread there at once, however slow or fast
 * the environment is; genuine concurrency will. This replaced an earlier version that compared
 * batch duration against a single-message baseline (serialized ~3x, concurrent ~1x) — sound in
 * principle, but the margin between those two shrinks on a slow/contended host to the point where
 * ordinary jitter crosses it, so it both intermittently failed on real concurrent processing and
 * risked passing on a genuine regression once the jitter went the other way. The thread-overlap
 * signal is not a timing proxy at all, so host speed does not affect it.
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
        // processed in this context are one-time and could otherwise hold a shared lock long
        // enough to mask real overlap on the very first batch this context ever sees.
        publishAndAwait(0);

        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> sampleUntilStopped(sampling, maxObservedConcurrency),
                "receive-concurrency-sampler");
        sampler.setDaemon(true);

        String[] requestIds = new String[PARTITIONS];
        sampler.start();
        try {
            for (int partition = 0; partition < PARTITIONS; partition++) {
                requestIds[partition] = publish(partition);
            }
            for (String requestId : requestIds) {
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                        assertTrue(messages.findByRequestId(requestId).isPresent()));
            }
        } finally {
            sampling.set(false);
            sampler.join(Duration.ofSeconds(5).toMillis());
        }

        assertTrue(maxObservedConcurrency.get() >= 2,
                "expected at least two of the three distinct-key messages to be observed inside "
                        + "PaymentRequestedConsumer.receive at the same sampled instant (threads="
                        + PARTITIONS + "); max concurrent observed = " + maxObservedConcurrency.get()
                        + " — looks serialized, not concurrent");
    }

    /**
     * Polls in a tight loop (no sleep) rather than on a fixed interval: the batch below completes
     * in well under a second, so the sampler needs sub-millisecond resolution to have any real
     * chance of catching an overlap — the same reason {@link ThreadMXBean#dumpAllThreads} isn't
     * used here, since capturing every live thread's full stack on each iteration would dominate
     * runtime instead of the batch itself. Depth is capped at 64: {@link PaymentRequestedConsumer#receive}
     * is always near the top of its thread's stack (a Kafka listener callback with a handful of
     * frames above the JDBC/network calls it makes), so 64 is a generous margin, not a tight fit.
     */
    private static void sampleUntilStopped(AtomicBoolean sampling, AtomicInteger maxObservedConcurrency) {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        while (sampling.get()) {
            int concurrentInReceive = 0;
            for (ThreadInfo info : threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds(), 64)) {
                if (isInsideReceive(info)) {
                    concurrentInReceive++;
                }
            }
            int observed = concurrentInReceive;
            maxObservedConcurrency.updateAndGet(previous -> Math.max(previous, observed));
        }
    }

    private static boolean isInsideReceive(ThreadInfo info) {
        if (info == null) {
            return false;
        }
        for (StackTraceElement frame : info.getStackTrace()) {
            if (PaymentRequestedConsumer.class.getName().equals(frame.getClassName())
                    && "receive".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
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
