package com.example.payments.api;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusResponse;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.Fees;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
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
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * API flow against real Kafka + Redis + Apicurio registry (Avro): POST returns 202
 * while processing continues; once a final event is published, the response consumer
 * correlates it and GET reflects COMPLETED. Requires a running Docker daemon.
 *
 * <p>Boots the context with {@link ApplicationContext#run(Class, Map)} (the pattern proven
 * in payment-sbus's ITs) instead of {@code @MicronautTest}/{@code TestPropertyProvider}:
 * the latter does not reliably override {@code application.yml} keys that already carry a
 * {@code ${ENV_VAR:default}} placeholder, so containers were silently connected to the
 * hardcoded localhost defaults instead of the dynamic testcontainer ports.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiFlowIT {

    private static final String API_KEY = "test-only-api-key";
    private static final String TEST_JWT_SECRET = "test-only-api-signing-secret-with-at-least-32-bytes";

    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private EmbeddedServer server;
    private HttpClient client;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        server = ApplicationContext.run(EmbeddedServer.class, properties());
        client = HttpClient.create(server.getURL());
    }

    @AfterAll
    void stop() {
        client.close();
        server.close();
    }

    static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }

    private static Map<String, Object> properties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "payment.simulation.wait-timeout", "1s",
                "payment.security.api-keys", java.util.List.of(API_KEY),
                "micronaut.security.token.jwt.signatures.secret.generator.secret", TEST_JWT_SECRET,
                "micronaut.security.token.jwt.signatures.secret.generator.jws-algorithm", "HS256");
    }

    @Test
    void returns202ThenCorrelatesFinalEvent() throws Exception {
        var request = new PaymentSimulationRequest(
                "MERCHANT-001", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");

        HttpResponse<StatusResponse> accepted = client.toBlocking().exchange(
                HttpRequest.POST("/payment-simulations", request).header("X-API-Key", API_KEY), StatusResponse.class);

        assertEquals(HttpStatus.ACCEPTED, accepted.getStatus());
        StatusResponse body = accepted.body();
        assertNotNull(body);
        String requestId = body.requestId();
        assertNotNull(requestId);

        // Simulate the SBUS publishing the final completed event (Avro).
        var result = new SimulationResult(
                UUID.randomUUID().toString(), requestId, SimulationResult.APPROVED, "123456",
                new BigDecimal("125.50"), "BRL", 3,
                new Fees(new BigDecimal("2.49"), new BigDecimal("1.25"), new BigDecimal("122.38")),
                new Settlement(LocalDate.now().plusDays(1), "D+1"), null, null);
        var envelope = EventEnvelope.create(
                EventTypes.PAYMENT_SIMULATION_COMPLETED, requestId,
                UUID.randomUUID().toString(), requestId, "trace", Sources.SBUS, result);

        AvroSerde serde = new AvroSerde(registryUrl());
        byte[] bytes = serde.serialize(Topics.COMPLETED, AvroMapper.toAvroCompleted(envelope));
        try (KafkaProducer<String, byte[]> producer = producer()) {
            producer.send(new ProducerRecord<>(Topics.COMPLETED, requestId, bytes)).get();
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            HttpResponse<StatusResponse> status = client.toBlocking().exchange(
                    HttpRequest.GET("/payment-simulations/" + requestId).header("X-API-Key", API_KEY), StatusResponse.class);
            assertEquals("COMPLETED", status.body().status().name());
            assertEquals("123456", status.body().result().authorizationCode());
        });
    }

    private KafkaProducer<String, byte[]> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(props);
    }
}
