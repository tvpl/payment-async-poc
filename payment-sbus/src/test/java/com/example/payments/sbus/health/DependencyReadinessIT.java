package com.example.payments.sbus.health;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
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
 * Proves AUD-09 end to end: {@code readiness-required: true} was declared for four dependencies
 * in {@code DependencyPolicies} but nothing ever actually checked them (readiness stayed UP no
 * matter what died), and a Registry outage during Avro decode was classified poison and
 * dead-lettered — a perfectly valid payment lost to a routine registry restart. Each test here
 * stops a REAL dependency container against a REAL running application and checks the REAL
 * observable effect, not a mock standing in for one.
 */
class DependencyReadinessIT {

    @Test
    void registryOutageTakesReadinessDownAndRoutesAConsumedRecordToRetryNotDlq() throws Exception {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        GenericContainer<?> apicurio = new GenericContainer<>(
                DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final")).withExposedPorts(8080);
        RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
        postgres.start();
        kafka.start();
        apicurio.start();
        redis.start();
        String registryUrl = "http://" + apicurio.getHost() + ":" + apicurio.getMappedPort(8080)
                + "/apis/registry/v2";

        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class,
                     properties(postgres, kafka, registryUrl, redis));
             HttpClient httpClient = HttpClient.create(server.getURL(), testHttpClientConfig());
             AvroSerde standaloneSerde = new AvroSerde(registryUrl);
             KafkaProducer<String, byte[]> producer = producer(kafka)) {

            // Readiness is UP while every dependency, including the registry, is healthy.
            assertEquals(HttpStatus.OK, readinessStatus(httpClient));

            // Serialize (and auto-register the schema) WHILE the registry is still up — a
            // standalone AvroSerde, deliberately separate from the app's own bean, so the app's
            // consumer-side deserializer has never resolved this global id and MUST make its own
            // network call to the registry when it decodes the record below.
            String requestId = UUID.randomUUID().toString();
            var payload = new PaymentSimulationRequestPayload(
                    "MERCHANT-001", new BigDecimal("50.00"), "BRL", "CREDIT_CARD", "VISA", 1,
                    "AUTHORIZE_AND_CAPTURE");
            var requested = EventEnvelope.create(EventTypes.PAYMENT_SIMULATION_REQUESTED,
                    requestId, UUID.randomUUID().toString(), requestId, "trace", Sources.API, payload);
            byte[] bytes = standaloneSerde.serialize(Topics.REQUESTED, AvroMapper.toAvroRequested(requested));

            apicurio.stop();

            // Readiness reflects the outage.
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, readinessStatus(httpClient)));

            // The real, running PaymentRequestedConsumer picks this up and tries to decode it
            // against the now-unreachable registry.
            producer.send(new ProducerRecord<>(Topics.REQUESTED, requestId, bytes)).get();

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertTrue(outboxRowExists(postgres, requestId, Topics.REQUESTED_RETRY),
                            "a registry connectivity failure must schedule a retry, not skip straight to poison"));
            assertFalse(outboxRowExists(postgres, requestId, Topics.DLQ),
                    "a registry outage must never dead-letter a record whose payload was never even inspected");
        } finally {
            postgres.stop();
            kafka.stop();
            redis.stop();
            if (apicurio.isRunning()) {
                apicurio.stop();
            }
        }
    }

    @Test
    void postgresOutageTakesReadinessDown() throws Exception {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        GenericContainer<?> apicurio = new GenericContainer<>(
                DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final")).withExposedPorts(8080);
        RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
        postgres.start();
        kafka.start();
        apicurio.start();
        redis.start();
        String registryUrl = "http://" + apicurio.getHost() + ":" + apicurio.getMappedPort(8080)
                + "/apis/registry/v2";

        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class,
                     properties(postgres, kafka, registryUrl, redis));
             HttpClient httpClient = HttpClient.create(server.getURL(), testHttpClientConfig())) {

            assertEquals(HttpStatus.OK, readinessStatus(httpClient));

            postgres.stop();

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, readinessStatus(httpClient)));
        } finally {
            kafka.stop();
            redis.stop();
            apicurio.stop();
        }
    }

    private static DefaultHttpClientConfiguration testHttpClientConfig() {
        var config = new DefaultHttpClientConfiguration();
        config.setReadTimeout(Duration.ofSeconds(30));
        return config;
    }

    private static HttpStatus readinessStatus(HttpClient client) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET("/health/readiness")).getStatus();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }

    private static boolean outboxRowExists(PostgreSQLContainer<?> postgres, String key, String topic)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                     postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM outbox_event WHERE message_key = ? AND topic = ?")) {
            statement.setString(1, key);
            statement.setString(2, topic);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1) > 0;
            }
        }
    }

    private static KafkaProducer<String, byte[]> producer(KafkaContainer kafka) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private static Map<String, Object> properties(PostgreSQLContainer<?> postgres, KafkaContainer kafka,
                                                   String registryUrl, RedisContainer redis) {
        return Map.ofEntries(
                Map.entry("micronaut.server.port", -1),
                Map.entry("kafka.bootstrap.servers", kafka.getBootstrapServers()),
                Map.entry("apicurio.registry.url", registryUrl),
                Map.entry("redis.uri", redis.getRedisURI()),
                Map.entry("datasources.default.url", postgres.getJdbcUrl() + "?stringtype=unspecified"),
                Map.entry("datasources.default.username", postgres.getUsername()),
                Map.entry("datasources.default.password", postgres.getPassword()),
                Map.entry("sbus.outbox.initial-delay", "200ms"),
                Map.entry("sbus.outbox.poll-interval", "200ms"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
