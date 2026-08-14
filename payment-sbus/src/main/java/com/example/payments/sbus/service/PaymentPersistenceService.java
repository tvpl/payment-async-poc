package com.example.payments.sbus.service;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.sbus.domain.IdempotencyRecord;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.domain.PaymentSbusMessage;
import com.example.payments.sbus.domain.SbusMessageStatus;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.repository.IdempotencyRecordRepository;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import com.example.payments.sbus.support.Json;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Transactional writes for the SBUS. Kept in a separate bean so the {@code @Transactional}
 * proxy actually applies (self-invocation from {@link PaymentSimulationService} would
 * bypass it). Each method writes the business state change <em>and</em> the outbox row in
 * a single commit — the dual-write guarantee. Avro serialization already happened OUTSIDE
 * these methods (no registry/network I/O while holding a DB connection).
 */
@Singleton
public class PaymentPersistenceService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentPersistenceService.class);
    private static final String AGGREGATE_TYPE = "PaymentSimulation";

    private final PaymentSbusMessageRepository messageRepository;
    private final OutboxEventRepository outboxRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final Json json;
    private final SbusMetrics metrics;

    public PaymentPersistenceService(PaymentSbusMessageRepository messageRepository,
                                     OutboxEventRepository outboxRepository,
                                     IdempotencyRecordRepository idempotencyRepository,
                                     Json json,
                                     SbusMetrics metrics) {
        this.messageRepository = messageRepository;
        this.outboxRepository = outboxRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.json = json;
        this.metrics = metrics;
    }

    /**
     * Read-only pre-check, run BEFORE any transaction and before a fresh {@code simulationId} is
     * minted for this request: is {@code idempotencyKey} already known under a DIFFERENT
     * requestId? That happens when the caller (payment-api) has already lost its own memory of
     * the mapping — its Redis idempotency-ttl (15m) can expire well before this table's window
     * (7d) — and retries with a brand new requestId carrying the same key. Previously
     * {@link #persistRequested} just returned in that case: no message row, no outbox, no final
     * event, ever, for the new requestId — a silent black hole. Returns the ORIGINAL message
     * this new request is a replay of, so the caller can resolve it (reusing its simulationId
     * and, if already terminal, its result) instead of starting a disconnected simulation.
     *
     * <p>The stored record's fingerprint (AUD-01) must match {@code currentFingerprint} for this
     * to count as a replay: an {@code Idempotency-Key} dedupes identical operations, not any two
     * operations that happen to share a key. A divergent fingerprint — or a legacy record whose
     * fingerprint predates this column (null) — means "not a replay"; the caller processes the
     * request as a brand-new simulation instead of copying another operation's result.
     */
    public Optional<PaymentSbusMessage> findReplayTarget(String idempotencyKey, String currentRequestId,
                                                          String currentFingerprint) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return idempotencyRepository.findByIdempotencyKey(idempotencyKey)
                .filter(record -> !record.getRequestId().equals(currentRequestId))
                .filter(record -> record.getFingerprint() != null
                        && record.getFingerprint().equals(currentFingerprint))
                .flatMap(record -> messageRepository.findByRequestId(record.getRequestId()));
    }

    /**
     * Resolves a replay whose original simulation already reached a terminal state: records the
     * new requestId against the SAME simulationId with the original's result copied over, and
     * publishes the final event for the new requestId (Avro bytes already serialized outside
     * this transaction, same discipline as every other outbox write here).
     *
     * <p>{@code resultJson} is the CALLER's already-corrected result (AUD-27) — its {@code
     * requestId} field rewritten to this replay's own requestId, not the original's — persisted
     * here verbatim so the DB-stored result and the published Avro payload never disagree with
     * each other about whose request this is.
     */
    @Transactional
    public void persistReplayFinal(EventEnvelope<PaymentSimulationRequestPayload> env, String idempotencyKey,
                                   PaymentSbusMessage original, String eventType, String topic,
                                   byte[] finalBytes, String headers, String resultJson) {
        if (messageRepository.findByRequestId(env.requestId()).isPresent()) {
            return;
        }
        PaymentSbusMessage replica = newReplicaRow(env, idempotencyKey, original.getSimulationId());
        replica.setStatus(original.getStatus());
        replica.setErrorCode(original.getErrorCode());
        replica.setErrorMessage(original.getErrorMessage());
        replica.setResult(resultJson);
        messageRepository.save(replica);
        saveOutbox(env.requestId(), eventType, topic, env.requestId(), finalBytes, headers);
        LOG.info("Resolved idempotency replay requestId={} against original={} simulationId={} "
                + "(already terminal, result copied)", env.requestId(), original.getRequestId(),
                original.getSimulationId());
    }

    /**
     * Resolves a replay whose original simulation was (at the caller's last read) still in
     * flight: records the new requestId against the SAME simulationId, PROCESSING, with no
     * outbox row yet. When the Core responds, {@link #persistFinal} finalizes every row sharing
     * that simulationId — including this one — and publishes a final event for each, so this
     * requestId gets its own terminal event too.
     *
     * <p>Race guarded here (AUD-11): the caller's "still in flight" read of {@code original} can
     * be stale by the time this transaction actually runs — the Core's response can land and
     * finalize the original in between. {@code PaymentSimulationService#handleCoreResponse} (via
     * {@link #findProcessingBySimulationId}) reads its own PROCESSING snapshot once, up front; a
     * replica inserted AFTER that snapshot was taken would never be picked up
     * by that finalization pass and would stay PROCESSING forever, since the Core answers a given
     * simulationId exactly once. Re-reading {@code original} fresh, inside this same transaction,
     * catches that: if it already went terminal, no PROCESSING row is inserted at all — the
     * caller gets the fresh terminal row back and must resolve this as a terminal replay instead
     * (same path as {@link #persistReplayFinal}), never as a stranded in-flight one.
     *
     * @return empty if the replica was registered as in-flight (the normal case); otherwise the
     *         freshly-read original, already terminal, for the caller to re-resolve
     */
    @Transactional
    public Optional<PaymentSbusMessage> registerReplayInFlight(EventEnvelope<PaymentSimulationRequestPayload> env,
                                       String idempotencyKey, PaymentSbusMessage original) {
        if (messageRepository.findByRequestId(env.requestId()).isPresent()) {
            return Optional.empty();
        }
        PaymentSbusMessage fresh = messageRepository.findByRequestId(original.getRequestId())
                .orElse(original);
        if (isTerminal(fresh.getStatus())) {
            LOG.info("Replay requestId={} against original={} simulationId={} found the original "
                    + "already terminal inside the transaction — resolving as a terminal replay "
                    + "instead of registering a stranded PROCESSING row", env.requestId(),
                    original.getRequestId(), original.getSimulationId());
            return Optional.of(fresh);
        }
        PaymentSbusMessage replica = newReplicaRow(env, idempotencyKey, original.getSimulationId());
        replica.setStatus(SbusMessageStatus.PROCESSING);
        messageRepository.save(replica);
        LOG.info("Registered idempotency replay requestId={} against in-flight original={} "
                + "simulationId={}", env.requestId(), original.getRequestId(), original.getSimulationId());
        return Optional.empty();
    }

    private PaymentSbusMessage newReplicaRow(EventEnvelope<PaymentSimulationRequestPayload> env,
                                             String idempotencyKey, String simulationId) {
        PaymentSbusMessage replica = new PaymentSbusMessage();
        replica.setRequestId(env.requestId());
        replica.setCorrelationId(env.correlationId());
        replica.setCausationId(env.causationId());
        replica.setIdempotencyKey(idempotencyKey);
        replica.setSimulationId(simulationId);
        replica.setPayload(json.toJson(env.payload()));
        return replica;
    }

    @Transactional
    public void persistRequested(EventEnvelope<PaymentSimulationRequestPayload> env,
                                 String idempotencyKey, String simulationId, String fingerprint,
                                 String eventType, String topic, byte[] commandBytes, String headers) {
        // Authoritative idempotency inside the tx (request_id UNIQUE is the backstop).
        if (messageRepository.findByRequestId(env.requestId()).isPresent()) {
            return;
        }
        Optional<IdempotencyRecord> existingKeyRecord = idempotencyKey == null
                ? Optional.empty() : idempotencyRepository.findByIdempotencyKey(idempotencyKey);
        if (existingKeyRecord.isPresent()
                && fingerprint.equals(existingKeyRecord.get().getFingerprint())) {
            // The SAME operation raced ahead of us between findReplayTarget's read and this
            // transaction: another in-flight request already claimed idempotencyKey -> requestId
            // for this exact fingerprint. Drop — that other transaction's row is authoritative.
            LOG.info("Duplicate idempotencyKey={} ignored requestId={}", idempotencyKey, env.requestId());
            return;
        }
        // existingKeyRecord present with a DIFFERENT (or legacy-null) fingerprint is not this
        // case: idempotencyKey is unique, so we cannot insert a second idempotency_record row for
        // it, but the message itself still gets processed as its own new, independent simulation
        // (AUD-01) — it is simply not tracked for future replay dedup under this key.

        PaymentSbusMessage message = new PaymentSbusMessage();
        message.setRequestId(env.requestId());
        message.setCorrelationId(env.correlationId());
        message.setCausationId(env.causationId());
        message.setIdempotencyKey(idempotencyKey);
        message.setSimulationId(simulationId);
        message.setStatus(SbusMessageStatus.PROCESSING);
        message.setPayload(json.toJson(env.payload()));
        messageRepository.save(message);

        if (idempotencyKey != null && existingKeyRecord.isEmpty()) {
            IdempotencyRecord record = new IdempotencyRecord();
            record.setIdempotencyKey(idempotencyKey);
            record.setRequestId(env.requestId());
            record.setStatus(SbusMessageStatus.PROCESSING.name());
            record.setFingerprint(fingerprint);
            idempotencyRepository.save(record);
        }

        saveOutbox(env.requestId(), eventType, topic, env.requestId(), commandBytes, headers);
        LOG.info("Persisted simulation and enqueued Core command requestId={} simulationId={}",
                env.requestId(), simulationId);
    }

    /**
     * Every row still PROCESSING for a simulationId — normally one, but an idempotency-key
     * replay (see {@link #registerReplayInFlight}) can leave more than one requestId waiting on
     * the same Core response. Read-only, outside any transaction: the caller serializes one
     * Avro final-event payload per row (each must carry ITS OWN requestId in the envelope, not
     * a copy of another row's bytes) before calling {@link #persistFinal} once per row.
     */
    public List<PaymentSbusMessage> findProcessingBySimulationId(String simulationId) {
        return messageRepository.findAllBySimulationId(simulationId).stream()
                .filter(m -> !isTerminal(m.getStatus()))
                .toList();
    }

    /**
     * Finalizes exactly the row {@code target} — fenced by primary key AND version, not by
     * simulationId alone, since a simulationId can now be shared by more than one row.
     */
    @Transactional
    public boolean persistFinal(PaymentSbusMessage target, boolean approved, String errorCode,
                                String errorMessage, String resultJson, String eventType,
                                String topic, byte[] finalBytes, String headers) {
        SbusMessageStatus terminal = approved
                ? SbusMessageStatus.COMPLETED
                : SbusMessageStatus.FAILED;
        Instant now = Instant.now();
        int updated = messageRepository.finalizeIfProcessing(
                target.getId(), target.getVersion(), terminal.name(), errorCode,
                errorMessage, resultJson, now);
        if (updated == 0) {
            return false;
        }

        saveOutbox(target.getRequestId(), eventType, topic, target.getRequestId(), finalBytes, headers);

        if (target.getCreatedAt() != null) {
            metrics.recordEndToEnd(Duration.between(target.getCreatedAt(), Instant.now()));
        }
        LOG.info("Recorded final event {} requestId={} simulationId={}",
                eventType, target.getRequestId(), target.getSimulationId());
        return true;
    }

    private void saveOutbox(String aggregateId, String eventType, String topic, String key,
                            byte[] payload, String headers) {
        OutboxEvent outbox = new OutboxEvent();
        outbox.setAggregateType(AGGREGATE_TYPE);
        outbox.setAggregateId(aggregateId);
        outbox.setEventType(eventType);
        outbox.setTopic(topic);
        outbox.setKey(key);
        outbox.setPayload(payload);
        outbox.setHeaders(headers);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setAttempts(0);
        outbox.setNextAttemptAt(Instant.now());
        outboxRepository.save(outbox);
    }

    private static boolean isTerminal(SbusMessageStatus status) {
        return status == SbusMessageStatus.COMPLETED || status == SbusMessageStatus.FAILED;
    }
}
