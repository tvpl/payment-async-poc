package com.example.payments.sbus;

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
import com.example.payments.sbus.service.PaymentPersistenceService;
import com.example.payments.sbus.service.PaymentSimulationService;
import com.example.payments.sbus.support.Json;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves task_fc6d987b Gap 2: an idempotency-key replay with a FRESH requestId used to vanish
 * without a trace — {@code persistRequested} just returned, no row, no outbox, no final event,
 * ever, for that requestId. This can happen legitimately: payment-api's Redis idempotency-ttl
 * (15m) can expire well before this table's own window (7d), so a client retry inside that gap
 * mints a brand new requestId carrying the same Idempotency-Key header.
 *
 * <p>Covers both timings a replay can arrive in: after the original simulation already finished
 * (resolved immediately, with the original's result copied) and while it is still in flight
 * (resolved once the Core responds, alongside the original — both requestIds get their own
 * terminal event from the same Core response).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyReplayIT {

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
    private PaymentPersistenceService persistence;
    private PaymentSbusMessageRepository messages;
    private Json json;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        service = context.getBean(PaymentSimulationService.class);
        persistence = context.getBean(PaymentPersistenceService.class);
        messages = context.getBean(PaymentSbusMessageRepository.class);
        json = context.getBean(Json.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void replayAfterTheOriginalAlreadyCompletedGetsItsOwnTerminalEventWithTheCopiedResult() throws Exception {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        String originalRequestId = UUID.randomUUID().toString();
        String replayRequestId = UUID.randomUUID().toString();

        var originalEnv = requestedEnvelope(originalRequestId, idempotencyKey);
        service.handleRequested(originalEnv, idempotencyKey, null);
        PaymentSbusMessage inFlight = messages.findByRequestId(originalRequestId).orElseThrow();
        service.handleCoreResponse(coreResponseEnvelope(originalEnv, inFlight.getSimulationId(), true));
        // Re-fetch: `inFlight` is a stale, pre-finalization snapshot (still PROCESSING, no
        // result) — only the row in the database was updated by handleCoreResponse.
        PaymentSbusMessage original = messages.findByRequestId(originalRequestId).orElseThrow();
        assertEquals(SbusMessageStatus.COMPLETED, original.getStatus());

        // The client's Idempotency-Key TTL expired on payment-api's side; it retries with a
        // fresh requestId carrying the same key.
        var replayEnv = requestedEnvelope(replayRequestId, idempotencyKey);
        service.handleRequested(replayEnv, idempotencyKey, null);

        PaymentSbusMessage replica = messages.findByRequestId(replayRequestId).orElseThrow(
                () -> new AssertionError("replay requestId was dropped — the black hole this test guards against"));
        assertEquals(SbusMessageStatus.COMPLETED, replica.getStatus());
        assertEquals(original.getSimulationId(), replica.getSimulationId());
        assertNotEquals(originalRequestId, replayRequestId);

        // task_T16 (AUD-27): the replay's own result carries the ORIGINAL's outcome (same
        // simulationId, status, amount, ...) but its OWN requestId — not a byte-for-byte copy of
        // the original's stored result, which would leave the original's requestId embedded
        // inside the replay's payload.
        SimulationResult originalResult = json.fromJson(original.getResult(), SimulationResult.class);
        SimulationResult replicaResult = json.fromJson(replica.getResult(), SimulationResult.class);
        assertEquals(replayRequestId, replicaResult.requestId(),
                "the payload's own requestId field must match the replay request that triggered "
                        + "it, not the original's");
        assertEquals(originalResult.simulationId(), replicaResult.simulationId());
        assertEquals(originalResult.status(), replicaResult.status());
        assertEquals(originalResult.amount(), replicaResult.amount());

        // The replay got its OWN outbox row (and therefore its own Kafka event), not a copy of
        // the original's — a caller polling specifically for replayRequestId must see it. The
        // original has two rows (its own core.command plus its own final event); the replay has
        // only the final one, since it never had a Core command of its own.
        assertEquals(1, outboxCountForKey(replayRequestId));
        assertEquals(2, outboxCountForKey(originalRequestId));
    }

    @Test
    void replayWhileTheOriginalIsStillInFlightIsFinalizedAlongsideItWhenTheCoreResponds() throws Exception {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        String originalRequestId = UUID.randomUUID().toString();
        String replayRequestId = UUID.randomUUID().toString();

        var originalEnv = requestedEnvelope(originalRequestId, idempotencyKey);
        service.handleRequested(originalEnv, idempotencyKey, null);
        PaymentSbusMessage original = messages.findByRequestId(originalRequestId).orElseThrow();
        assertEquals(SbusMessageStatus.PROCESSING, original.getStatus());

        // The replay arrives before the Core has answered.
        var replayEnv = requestedEnvelope(replayRequestId, idempotencyKey);
        service.handleRequested(replayEnv, idempotencyKey, null);
        PaymentSbusMessage replica = messages.findByRequestId(replayRequestId).orElseThrow(
                () -> new AssertionError("replay requestId was dropped while the original was still in flight"));
        assertEquals(SbusMessageStatus.PROCESSING, replica.getStatus());
        assertEquals(original.getSimulationId(), replica.getSimulationId());
        assertEquals(0, outboxCountForKey(replayRequestId), "no terminal event yet — the Core hasn't answered");

        // The Core answers once, correlated by simulationId — both requestIds must be finalized.
        service.handleCoreResponse(coreResponseEnvelope(originalEnv, original.getSimulationId(), true));

        assertEquals(SbusMessageStatus.COMPLETED, messages.findByRequestId(originalRequestId).orElseThrow().getStatus());
        assertEquals(SbusMessageStatus.COMPLETED, messages.findByRequestId(replayRequestId).orElseThrow().getStatus());
        // The original has its own core.command row plus its own final event (2); the replay,
        // registered while in flight with no command of its own, gets only the final event (1).
        assertEquals(2, outboxCountForKey(originalRequestId));
        assertEquals(1, outboxCountForKey(replayRequestId));
    }

    /**
     * task_T10 (AUD-01): a replay with the SAME idempotency key but a DIFFERENT payload used to
     * be resolved by key alone — the second, genuinely different operation got the original's
     * result copied onto it (a wrong-value bug: the caller asked to simulate one payment and
     * received another's outcome). The fingerprint gate means a divergent payload is not a
     * replay at all; it must be processed as its own new simulation, all the way to its own
     * Core round-trip and its own result.
     */
    @Test
    void sameKeyWithDivergentPayloadProcessesAsANewSimulationInsteadOfCopyingTheOriginalsResult() throws Exception {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        String originalRequestId = UUID.randomUUID().toString();
        String divergentRequestId = UUID.randomUUID().toString();

        var originalEnv = requestedEnvelope(originalRequestId, idempotencyKey);
        service.handleRequested(originalEnv, idempotencyKey, null);
        PaymentSbusMessage inFlight = messages.findByRequestId(originalRequestId).orElseThrow();
        service.handleCoreResponse(coreResponseEnvelope(originalEnv, inFlight.getSimulationId(), true));
        PaymentSbusMessage original = messages.findByRequestId(originalRequestId).orElseThrow();
        assertEquals(SbusMessageStatus.COMPLETED, original.getStatus());

        // Same idempotency key, but a genuinely different payload (amount differs) — a divergent
        // fingerprint, not a replay.
        var divergentEnv = requestedEnvelope(divergentRequestId, idempotencyKey, new BigDecimal("999.00"));
        service.handleRequested(divergentEnv, idempotencyKey, null);

        PaymentSbusMessage divergent = messages.findByRequestId(divergentRequestId).orElseThrow(
                () -> new AssertionError("divergent-payload request was dropped instead of being processed as new"));
        assertNotEquals(original.getSimulationId(), divergent.getSimulationId(),
                "a divergent payload must get its own simulation, not reuse the original's");
        assertEquals(SbusMessageStatus.PROCESSING, divergent.getStatus(),
                "it must go through its own Core round-trip, not copy the original's terminal result");

        // Finalize it with a DIFFERENT outcome than the original to prove the result is genuinely
        // its own, not a copy.
        service.handleCoreResponse(coreResponseEnvelope(divergentEnv, divergent.getSimulationId(), false));
        PaymentSbusMessage divergentFinal = messages.findByRequestId(divergentRequestId).orElseThrow();
        assertEquals(SbusMessageStatus.FAILED, divergentFinal.getStatus());
        assertNotEquals(original.getResult(), divergentFinal.getResult());
        // Its own outbox trail too: one core.command row plus its own final event, same shape as
        // any brand-new simulation — not the single "final event only" shape a real replay gets.
        assertEquals(2, outboxCountForKey(divergentRequestId));
    }

    /**
     * task_T10 (AUD-01): a row written before the fingerprint column existed has
     * {@code fingerprint IS NULL}. It must never be treated as a replay target — there is no
     * fingerprint to compare against, so honesty requires "not a replay", not "assume it matches".
     */
    @Test
    void legacyRecordWithNullFingerprintIsTreatedAsNonReplay() throws Exception {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        String legacyRequestId = UUID.randomUUID().toString();
        String freshRequestId = UUID.randomUUID().toString();

        var legacyEnv = requestedEnvelope(legacyRequestId, idempotencyKey);
        service.handleRequested(legacyEnv, idempotencyKey, null);
        PaymentSbusMessage legacyInFlight = messages.findByRequestId(legacyRequestId).orElseThrow();
        service.handleCoreResponse(coreResponseEnvelope(legacyEnv, legacyInFlight.getSimulationId(), true));
        clearFingerprint(idempotencyKey);

        var freshEnv = requestedEnvelope(freshRequestId, idempotencyKey);
        service.handleRequested(freshEnv, idempotencyKey, null);

        PaymentSbusMessage fresh = messages.findByRequestId(freshRequestId).orElseThrow(
                () -> new AssertionError("request against a null-fingerprint legacy row was dropped"));
        PaymentSbusMessage legacy = messages.findByRequestId(legacyRequestId).orElseThrow();
        assertNotEquals(legacy.getSimulationId(), fresh.getSimulationId(),
                "a null-fingerprint legacy row must never be treated as a replay target");
        assertEquals(SbusMessageStatus.PROCESSING, fresh.getStatus());
    }

    /**
     * task_T11 (AUD-11): registerReplayInFlight used to trust the caller's "still PROCESSING"
     * read of the original without re-checking. If the Core's response landed and finalized the
     * original in the window between that read and this transaction, the replica got registered
     * PROCESSING anyway — and since {@code handleCoreResponse} reads its PROCESSING snapshot only
     * once per Core response, a replica inserted after that snapshot is never picked up: it stays
     * PROCESSING forever. This interleaves the exact ordering that produces that race — the
     * original is fully finalized (the Core response already read AND applied) BEFORE the replay
     * registration transaction runs with a deliberately stale "still PROCESSING" snapshot, the
     * same snapshot {@code PaymentSimulationService#resolveReplay} would have read a moment
     * earlier — and proves the fix: no PROCESSING row is left behind; the fresh, already-terminal
     * original is handed back instead.
     */
    @Test
    void replayRegisteredWithAStaleInFlightSnapshotAfterTheOriginalAlreadyFinalizedIsNotStranded() throws Exception {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        String originalRequestId = UUID.randomUUID().toString();
        String replayRequestId = UUID.randomUUID().toString();

        var originalEnv = requestedEnvelope(originalRequestId, idempotencyKey);
        service.handleRequested(originalEnv, idempotencyKey, null);
        // The stale snapshot: exactly what a concurrent replay-registration path would have read
        // BEFORE the Core response below finalizes the original.
        PaymentSbusMessage staleOriginal = messages.findByRequestId(originalRequestId).orElseThrow();
        assertEquals(SbusMessageStatus.PROCESSING, staleOriginal.getStatus());

        // "Core response read" through "finalization": fully applied and committed before the
        // replay registration below ever runs.
        service.handleCoreResponse(coreResponseEnvelope(originalEnv, staleOriginal.getSimulationId(), true));
        assertEquals(SbusMessageStatus.COMPLETED, messages.findByRequestId(originalRequestId).orElseThrow().getStatus());

        // "replay registrado": registerReplayInFlight runs with the STALE (still-PROCESSING)
        // snapshot captured above.
        var replayEnv = requestedEnvelope(replayRequestId, idempotencyKey);
        var nowTerminal = persistence.registerReplayInFlight(replayEnv, idempotencyKey, null, staleOriginal);

        assertTrue(nowTerminal.isPresent(),
                "registerReplayInFlight must detect the original already went terminal inside the "
                        + "transaction and hand back the fresh row instead of registering a stranded one");
        assertEquals(SbusMessageStatus.COMPLETED, nowTerminal.get().getStatus());
        assertTrue(messages.findByRequestId(replayRequestId).isEmpty(),
                "no PROCESSING row must be left behind for the replay — the caller resolves it as "
                        + "terminal instead, never as an orphan waiting for a Core response that "
                        + "already came and went");
    }

    private void clearFingerprint(String idempotencyKey) throws Exception {
        try (var connection = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(
                     "UPDATE idempotency_record SET fingerprint = NULL WHERE idempotency_key = ?")) {
            statement.setString(1, idempotencyKey);
            statement.executeUpdate();
        }
    }

    private static EventEnvelope<PaymentSimulationRequestPayload> requestedEnvelope(String requestId, String idempotencyKey) {
        return requestedEnvelope(requestId, idempotencyKey, new BigDecimal("50.00"));
    }

    private static EventEnvelope<PaymentSimulationRequestPayload> requestedEnvelope(
            String requestId, String idempotencyKey, BigDecimal amount) {
        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", amount, "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
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
