package com.example.payments.sbus.service;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.ProcessPaymentSimulationCommandPayload;
import com.example.payments.common.model.SimulationResult;
import com.example.payments.sbus.domain.PaymentSbusMessage;
import com.example.payments.sbus.domain.SbusMessageStatus;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import com.example.payments.sbus.support.HeaderMap;
import com.example.payments.sbus.support.Json;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates SBUS handling. Builds and <strong>serializes</strong> the Avro events
 * (registry/network I/O) <em>outside</em> any DB transaction, then delegates the atomic
 * writes (state + outbox) to {@link PaymentPersistenceService}. This keeps DB connections
 * free of external calls and preserves the transactional-outbox guarantee.
 */
@Singleton
public class PaymentSimulationService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentSimulationService.class);

    private final PaymentSbusMessageRepository messageRepository;
    private final PaymentPersistenceService persistence;
    private final Json json;
    private final AvroSerde avroSerde;

    public PaymentSimulationService(PaymentSbusMessageRepository messageRepository,
                                    PaymentPersistenceService persistence,
                                    Json json,
                                    AvroSerde avroSerde) {
        this.messageRepository = messageRepository;
        this.persistence = persistence;
        this.json = json;
        this.avroSerde = avroSerde;
    }

    public void handleRequested(EventEnvelope<PaymentSimulationRequestPayload> env,
                                String idempotencyKey, String traceparent) {
        if (messageRepository.findByRequestId(env.requestId()).isPresent()) {
            LOG.info("Duplicate PaymentSimulationRequested ignored requestId={}", env.requestId());
            return;
        }

        // Canonical fingerprint of THIS payload (AUD-01) — computed from what the SBUS actually
        // received, never trusted from an API-asserted header, since this decides whether a
        // request reuses another operation's result.
        String fingerprint = IdempotencyFingerprint.of(env.payload());

        // TEN-06/edge case: an empty tenantId (envelope predates tenant scoping, or the caller
        // never set one) falls back to the synthetic "legacy" tenant rather than erroring — the
        // same fallback the V12 migration applies to pre-existing rows.
        String tenantId = effectiveTenant(env.tenantId());

        // An idempotency-key replay with a FRESH requestId AND a MATCHING fingerprint: the caller
        // (payment-api) has already lost its own memory of the original mapping (its Redis
        // idempotency-ttl can expire well before this table's own window) and is retrying with a
        // brand new requestId carrying the same key. Resolve it against the original simulation
        // instead of silently dropping it — see PaymentPersistenceService#findReplayTarget's
        // javadoc for the failure this replaces. A DIVERGENT fingerprint (or no match at all) is
        // not a replay — it falls through and is processed as its own new simulation below.
        Optional<PaymentSbusMessage> replayTarget =
                persistence.findReplayTarget(tenantId, idempotencyKey, env.requestId(), fingerprint);
        if (replayTarget.isPresent()) {
            resolveReplay(env, idempotencyKey, traceparent, replayTarget.get());
            return;
        }

        String simulationId = UUID.randomUUID().toString();
        EventEnvelope<ProcessPaymentSimulationCommandPayload> command = env.deriveAs(
                EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND,
                Sources.SBUS,
                new ProcessPaymentSimulationCommandPayload(simulationId, env.payload()));

        // Avro serialization OUTSIDE the transaction.
        byte[] commandBytes = avroSerde.serialize(Topics.CORE_COMMAND, AvroMapper.toAvroCommand(command));
        String headers = json.toJson(HeaderMap.from(command, traceparent));

        persistence.persistRequested(env, tenantId, idempotencyKey, traceparent, simulationId, fingerprint,
                EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND, Topics.CORE_COMMAND, commandBytes, headers);
    }

    /**
     * TEN-06: the effective tenant for a request whose envelope carries no tenant identity (empty
     * or blank {@code tenantId} — an envelope predating tenant scoping, or a caller that never set
     * one). Mirrors the V12 migration's own {@code DEFAULT 'legacy'} so pre-existing and
     * tenant-less traffic land in the same bucket instead of erroring.
     */
    private static String effectiveTenant(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? "legacy" : tenantId;
    }

    private void resolveReplay(EventEnvelope<PaymentSimulationRequestPayload> env, String idempotencyKey,
                               String traceparent, PaymentSbusMessage original) {
        if (!isTerminal(original.getStatus())) {
            // Original simulation still in flight (as of our last read): record the new requestId
            // against the same simulationId now, PROCESSING — persistFinal (see
            // handleCoreResponse) publishes its own terminal event for it once the Core responds,
            // same as any other row.
            Optional<PaymentSbusMessage> nowTerminal =
                    persistence.registerReplayInFlight(env, idempotencyKey, traceparent, original);
            if (nowTerminal.isEmpty()) {
                return;
            }
            // AUD-11: the original actually finalized between our read above and the
            // registration transaction — registerReplayInFlight declined to leave a PROCESSING
            // row that would never be picked up by a Core response that already arrived. Fall
            // through and re-resolve this exactly like an already-terminal replay, using the
            // FRESH row it handed back.
            original = nowTerminal.get();
        }

        // Original already terminal: build and publish a final event for the NEW requestId, with
        // the ORIGINAL's stored result. The envelope is built manually (not via env.deriveAs,
        // which would carry the REQUEST's requestId, not this correlation) so the new requestId
        // ends up in the outbox row's key. The stored result's OWN requestId field (AUD-27) is
        // rewritten too — it was minted for the ORIGINAL request and, left alone, would publish
        // (and persist) the replay's terminal event carrying someone else's requestId inside its
        // own payload, not just in the Kafka envelope.
        boolean approved = original.getStatus() == SbusMessageStatus.COMPLETED;
        SimulationResult storedResult = json.fromJson(original.getResult(), SimulationResult.class);
        SimulationResult result = new SimulationResult(
                storedResult.simulationId(), env.requestId(), storedResult.status(),
                storedResult.authorizationCode(), storedResult.amount(), storedResult.currency(),
                storedResult.installments(), storedResult.fees(), storedResult.settlement(),
                storedResult.errorCode(), storedResult.errorMessage());
        String finalType = approved ? EventTypes.PAYMENT_SIMULATION_COMPLETED : EventTypes.PAYMENT_SIMULATION_FAILED;
        String finalTopic = approved ? Topics.COMPLETED : Topics.FAILED;
        EventEnvelope<SimulationResult> finalEvent = new EventEnvelope<>(
                UUID.randomUUID().toString(), finalType, EventEnvelope.CURRENT_VERSION, java.time.Instant.now(),
                env.requestId(), env.correlationId(), env.eventId(), env.traceId(), Sources.SBUS,
                original.getTenantId(), result);

        byte[] finalBytes = approved
                ? avroSerde.serialize(finalTopic, AvroMapper.toAvroCompleted(finalEvent))
                : avroSerde.serialize(finalTopic, AvroMapper.toAvroFailed(finalEvent));
        String headers = json.toJson(HeaderMap.from(finalEvent, traceparent));

        persistence.persistReplayFinal(env, idempotencyKey, traceparent, original, finalType, finalTopic,
                finalBytes, headers, json.toJson(result));
    }

    public void handleCoreResponse(EventEnvelope<CorePaymentSimulationResponsePayload> env) {
        CorePaymentSimulationResponsePayload core = env.payload();
        // A simulationId can be shared by more than one row (an idempotency-key replay records
        // its fresh requestId against the same simulation — see PaymentPersistenceService). Every
        // row still PROCESSING gets its own terminal event, each with ITS OWN requestId in the
        // envelope — reusing one row's serialized bytes for another would ship the wrong
        // requestId inside the payload.
        List<PaymentSbusMessage> pending = persistence.findProcessingBySimulationId(core.simulationId());
        if (pending.isEmpty()) {
            LOG.info("Core response for simulationId={} (requestId={}) matches no row still "
                    + "PROCESSING — unknown simulation or duplicate response, ignoring",
                    core.simulationId(), env.requestId());
            return;
        }

        boolean approved = SimulationResult.APPROVED.equalsIgnoreCase(core.status());
        String finalType = approved
                ? EventTypes.PAYMENT_SIMULATION_COMPLETED
                : EventTypes.PAYMENT_SIMULATION_FAILED;
        String finalTopic = approved ? Topics.COMPLETED : Topics.FAILED;

        for (PaymentSbusMessage message : pending) {
            SimulationResult result = new SimulationResult(
                    core.simulationId(), message.getRequestId(), core.status(), core.authorizationCode(),
                    core.amount(), core.currency(), core.installments(), core.fees(), core.settlement(),
                    core.errorCode(), core.errorMessage());
            EventEnvelope<SimulationResult> finalEvent = new EventEnvelope<>(
                    UUID.randomUUID().toString(), finalType, EventEnvelope.CURRENT_VERSION, java.time.Instant.now(),
                    message.getRequestId(), message.getCorrelationId(), env.eventId(), env.traceId(),
                    Sources.SBUS, message.getTenantId(), result);

            // Avro serialization OUTSIDE the transaction.
            byte[] finalBytes = approved
                    ? avroSerde.serialize(finalTopic, AvroMapper.toAvroCompleted(finalEvent))
                    : avroSerde.serialize(finalTopic, AvroMapper.toAvroFailed(finalEvent));
            // OBS-02: the traceparent captured at THIS row's own ingestion (persisted by
            // persistRequested/persistReplayFinal), not the Core response's — the final event's
            // trace context traces back to the request that originated it, not to the (unrelated)
            // trace of whatever triggered the Core to answer now.
            String headers = json.toJson(HeaderMap.from(finalEvent, message.getTraceparent()));

            persistence.persistFinal(message, approved, core.errorCode(), core.errorMessage(),
                    json.toJson(result), finalType, finalTopic, finalBytes, headers);
        }
    }

    private static boolean isTerminal(SbusMessageStatus status) {
        return status == SbusMessageStatus.COMPLETED || status == SbusMessageStatus.FAILED;
    }
}
