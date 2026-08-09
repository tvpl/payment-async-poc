package com.example.payments.sbus;

import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.domain.PaymentSbusMessage;
import com.example.payments.sbus.domain.SbusMessageStatus;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import com.example.payments.sbus.service.PaymentPersistenceService;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminalTransitionIT {

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
    private PaymentSbusMessageRepository messages;
    private PaymentPersistenceService persistence;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        messages = context.getBean(PaymentSbusMessageRepository.class);
        persistence = context.getBean(PaymentPersistenceService.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void concurrentOppositeFinalizationsChooseOneCoherentTerminal() throws Exception {
        PaymentSbusMessage message = processingMessage();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> approved = executor.submit(() -> {
                start.await();
                return finalizeApproved(message.getSimulationId(), new byte[]{1});
            });
            Future<Boolean> failed = executor.submit(() -> {
                start.await();
                return finalizeFailed(message.getSimulationId(), new byte[]{2});
            });
            start.countDown();

            assertEquals(1, (approved.get() ? 1 : 0) + (failed.get() ? 1 : 0));
        }

        PaymentSbusMessage stored = messages.findBySimulationId(message.getSimulationId()).orElseThrow();
        TerminalOutbox outbox = terminalOutbox(message.getRequestId());
        assertEquals(1, outbox.count());
        if (stored.getStatus() == SbusMessageStatus.COMPLETED) {
            assertEquals(EventTypes.PAYMENT_SIMULATION_COMPLETED, outbox.eventType());
            assertEquals(Topics.COMPLETED, outbox.topic());
            assertArrayEquals(new byte[]{1}, outbox.payload());
        } else {
            assertEquals(SbusMessageStatus.FAILED, stored.getStatus());
            assertEquals(EventTypes.PAYMENT_SIMULATION_FAILED, outbox.eventType());
            assertEquals(Topics.FAILED, outbox.topic());
            assertArrayEquals(new byte[]{2}, outbox.payload());
        }
    }

    @Test
    void concurrentEquivalentFinalizationsCreateOneOutbox() throws Exception {
        PaymentSbusMessage message = processingMessage();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return finalizeApproved(message.getSimulationId(), new byte[]{3});
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return finalizeApproved(message.getSimulationId(), new byte[]{3});
            });
            start.countDown();

            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        }

        assertEquals(SbusMessageStatus.COMPLETED,
                messages.findBySimulationId(message.getSimulationId()).orElseThrow().getStatus());
        assertEquals(1, terminalOutbox(message.getRequestId()).count());
    }

    @Test
    void sequentialDuplicateIsIdempotent() throws SQLException {
        PaymentSbusMessage message = processingMessage();

        assertTrue(finalizeApproved(message.getSimulationId(), new byte[]{4}));
        assertFalse(finalizeApproved(message.getSimulationId(), new byte[]{4}));
        assertEquals(1, terminalOutbox(message.getRequestId()).count());
    }

    @Test
    void laterConflictingTerminalCannotOverwriteWinner() throws SQLException {
        PaymentSbusMessage message = processingMessage();

        assertTrue(finalizeFailed(message.getSimulationId(), new byte[]{5}));
        assertFalse(finalizeApproved(message.getSimulationId(), new byte[]{6}));

        PaymentSbusMessage stored = messages.findBySimulationId(message.getSimulationId()).orElseThrow();
        TerminalOutbox outbox = terminalOutbox(message.getRequestId());
        assertEquals(SbusMessageStatus.FAILED, stored.getStatus());
        assertEquals(EventTypes.PAYMENT_SIMULATION_FAILED, outbox.eventType());
        assertArrayEquals(new byte[]{5}, outbox.payload());
        assertEquals(1, outbox.count());
    }

    @Test
    void outboxFailureRollsBackTerminalState() throws SQLException {
        PaymentSbusMessage message = processingMessage();

        assertThrows(RuntimeException.class, () -> persistence.persistFinal(
                message.getSimulationId(), true, null, null, "{}", null,
                Topics.COMPLETED, new byte[]{7}, "{}"));

        assertEquals(SbusMessageStatus.PROCESSING,
                messages.findBySimulationId(message.getSimulationId()).orElseThrow().getStatus());
        assertEquals(0, terminalOutbox(message.getRequestId()).count());
    }

    private PaymentSbusMessage processingMessage() {
        String id = UUID.randomUUID().toString();
        PaymentSbusMessage message = new PaymentSbusMessage();
        message.setRequestId(id);
        message.setCorrelationId(id);
        message.setCausationId(id);
        message.setSimulationId("sim-" + id);
        message.setStatus(SbusMessageStatus.PROCESSING);
        message.setPayload("{}");
        return messages.save(message);
    }

    private boolean finalizeApproved(String simulationId, byte[] payload) {
        return persistence.persistFinal(simulationId, true, null, null, "{}",
                EventTypes.PAYMENT_SIMULATION_COMPLETED, Topics.COMPLETED, payload, "{}");
    }

    private boolean finalizeFailed(String simulationId, byte[] payload) {
        return persistence.persistFinal(simulationId, false, "DECLINED", "declined", "{}",
                EventTypes.PAYMENT_SIMULATION_FAILED, Topics.FAILED, payload, "{}");
    }

    private TerminalOutbox terminalOutbox(String requestId) throws SQLException {
        try (var connection = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT count(*) OVER (), event_type, topic, payload
                     FROM outbox_event
                     WHERE aggregate_id = ?
                       AND event_type IN (?, ?)
                     ORDER BY id
                     LIMIT 1
                     """)) {
            statement.setString(1, requestId);
            statement.setString(2, EventTypes.PAYMENT_SIMULATION_COMPLETED);
            statement.setString(3, EventTypes.PAYMENT_SIMULATION_FAILED);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return new TerminalOutbox(0, null, null, null);
                }
                return new TerminalOutbox(result.getLong(1), result.getString(2),
                        result.getString(3), result.getBytes(4));
            }
        }
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

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080)
                + "/apis/registry/v2";
    }

    private record TerminalOutbox(long count, String eventType, String topic, byte[] payload) {
    }
}
