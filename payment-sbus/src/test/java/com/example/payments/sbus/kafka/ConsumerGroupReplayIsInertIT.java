package com.example.payments.sbus.kafka;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.common.model.Fees;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;
import com.example.payments.sbus.domain.PaymentSbusMessage;
import com.example.payments.sbus.domain.SbusMessageStatus;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import com.example.payments.sbus.service.PaymentSimulationService;
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

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task_T15 (AUD-10): proves the property the new dedicated consumer groups
 * ({@code payment-sbus-requested}, {@code payment-sbus-core-response} — see
 * {@link PaymentRequestedConsumer} / {@link CoreResponseConsumer}) rely on for safety, BEFORE the
 * rename — new consumer groups reread each topic's full history once (EARLIEST offset reset on a
 * group with no prior committed offsets). That is only safe because replaying an
 * already-processed record is a genuine no-op at the business layer, not just "doesn't crash".
 *
 * <p>Runs the same handler methods a real replayed Kafka delivery would invoke, twice, and
 * asserts the second call changes nothing observable: no second row, no extra outbox event, no
 * status change.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerGroupReplayIsInertIT {

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
    private PaymentSimulationService service;
    private PaymentSbusMessageRepository messages;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        service = context.getBean(PaymentSimulationService.class);
        messages = context.getBean(PaymentSbusMessageRepository.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void processingTheSameRequestedRecordTwiceIsANoOp() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var env = requestedEnvelope(requestId);

        service.handleRequested(env, null, null);
        PaymentSbusMessage firstPass = messages.findByRequestId(requestId).orElseThrow();
        assertEquals(SbusMessageStatus.PROCESSING, firstPass.getStatus());
        int outboxAfterFirstPass = outboxCountForKey(requestId);
        assertEquals(1, outboxAfterFirstPass, "the core.command row from the first pass");

        // A new consumer group's EARLIEST reset redelivers this exact record.
        service.handleRequested(env, null, null);

        PaymentSbusMessage secondPass = messages.findByRequestId(requestId).orElseThrow();
        assertEquals(firstPass.getVersion(), secondPass.getVersion(),
                "the row must be completely untouched by the replay, not just present");
        assertEquals(SbusMessageStatus.PROCESSING, secondPass.getStatus());
        assertEquals(outboxAfterFirstPass, outboxCountForKey(requestId),
                "no second core.command must be enqueued for the same requestId");
    }

    @Test
    void coreResponseForAnAlreadyTerminalSimulationIsIgnored() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var requestedEnv = requestedEnvelope(requestId);
        service.handleRequested(requestedEnv, null, null);
        String simulationId = messages.findByRequestId(requestId).orElseThrow().getSimulationId();

        var coreResponseEnv = coreResponseEnvelope(requestedEnv, simulationId, true);
        service.handleCoreResponse(coreResponseEnv);
        PaymentSbusMessage terminal = messages.findByRequestId(requestId).orElseThrow();
        assertEquals(SbusMessageStatus.COMPLETED, terminal.getStatus());
        int outboxAfterFirstResponse = outboxCountForKey(requestId);
        assertEquals(2, outboxAfterFirstResponse, "core.command + the completed event");

        // A new consumer group's EARLIEST reset redelivers the same Core response.
        service.handleCoreResponse(coreResponseEnv);

        PaymentSbusMessage afterReplay = messages.findByRequestId(requestId).orElseThrow();
        assertEquals(terminal.getVersion(), afterReplay.getVersion(),
                "an already-terminal simulation must be completely untouched by the replay");
        assertEquals(SbusMessageStatus.COMPLETED, afterReplay.getStatus());
        assertEquals(outboxAfterFirstResponse, outboxCountForKey(requestId),
                "no second completed event must be published for the same requestId");
    }

    private static EventEnvelope<PaymentSimulationRequestPayload> requestedEnvelope(String requestId) {
        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("50.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
        return EventEnvelope.create(EventTypes.PAYMENT_SIMULATION_REQUESTED,
                requestId, UUID.randomUUID().toString(), requestId, "trace-" + requestId, Sources.API, payload);
    }

    private static EventEnvelope<CorePaymentSimulationResponsePayload> coreResponseEnvelope(
            EventEnvelope<PaymentSimulationRequestPayload> requested, String simulationId, boolean approved) {
        var payload = new CorePaymentSimulationResponsePayload(
                simulationId, approved ? SimulationResult.APPROVED : "DECLINED", "654321",
                new BigDecimal("50.00"), "BRL", 1,
                new Fees(new BigDecimal("1.00"), new BigDecimal("0.50"), new BigDecimal("48.50")),
                new Settlement(LocalDate.now().plusDays(1), "D+1"), null, null);
        return requested.deriveAs(EventTypes.CORE_PAYMENT_SIMULATION_RESPONSE, Sources.CORE, payload);
    }

    private int outboxCountForKey(String key) throws Exception {
        try (var connection = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM outbox_event WHERE message_key = ?")) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
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
