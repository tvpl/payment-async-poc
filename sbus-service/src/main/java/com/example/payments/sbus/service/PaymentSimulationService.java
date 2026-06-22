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
import com.example.payments.sbus.domain.IdempotencyRecord;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.domain.PaymentSbusMessage;
import com.example.payments.sbus.domain.SbusMessageStatus;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.repository.IdempotencyRecordRepository;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import com.example.payments.sbus.support.HeaderMap;
import com.example.payments.sbus.support.Json;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Heart of the SBUS. Every method runs in a single DB transaction that writes the
 * business state change <em>and</em> the outbox row atomically (the dual-write
 * problem solved by the transactional outbox pattern). Kafka publication happens
 * later, out of band, in {@code OutboxPublisher}.
 */
@Singleton
public class PaymentSimulationService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentSimulationService.class);
    private static final String AGGREGATE_TYPE = "PaymentSimulation";

    private final PaymentSbusMessageRepository messageRepository;
    private final OutboxEventRepository outboxRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final Json json;
    private final SbusMetrics metrics;
    private final AvroSerde avroSerde;

    public PaymentSimulationService(PaymentSbusMessageRepository messageRepository,
                                    OutboxEventRepository outboxRepository,
                                    IdempotencyRecordRepository idempotencyRepository,
                                    Json json,
                                    SbusMetrics metrics,
                                    AvroSerde avroSerde) {
        this.messageRepository = messageRepository;
        this.outboxRepository = outboxRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.json = json;
        this.metrics = metrics;
        this.avroSerde = avroSerde;
    }

    /**
     * Step 1-5 of the outbox flow: persist intent + enqueue the Core command.
     * Idempotent on requestId / idempotencyKey, so redeliveries are no-ops.
     */
    @Transactional
    public void handleRequested(EventEnvelope<PaymentSimulationRequestPayload> env,
                                String idempotencyKey, String traceparent) {
        if (messageRepository.findByRequestId(env.requestId()).isPresent()) {
            LOG.info("Duplicate PaymentSimulationRequested ignored requestId={}", env.requestId());
            return;
        }
        if (idempotencyKey != null && idempotencyRepository.existsByIdempotencyKey(idempotencyKey)) {
            LOG.info("Duplicate idempotencyKey={} ignored requestId={}", idempotencyKey, env.requestId());
            return;
        }

        String simulationId = UUID.randomUUID().toString();

        PaymentSbusMessage message = new PaymentSbusMessage();
        message.setRequestId(env.requestId());
        message.setCorrelationId(env.correlationId());
        message.setCausationId(env.causationId());
        message.setIdempotencyKey(idempotencyKey);
        message.setSimulationId(simulationId);
        message.setStatus(SbusMessageStatus.PROCESSING);
        message.setPayload(json.toJson(env.payload()));
        messageRepository.save(message);

        if (idempotencyKey != null) {
            IdempotencyRecord record = new IdempotencyRecord();
            record.setIdempotencyKey(idempotencyKey);
            record.setRequestId(env.requestId());
            record.setStatus(SbusMessageStatus.PROCESSING.name());
            idempotencyRepository.save(record);
        }

        EventEnvelope<ProcessPaymentSimulationCommandPayload> command = env.deriveAs(
                EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND,
                Sources.SBUS,
                new ProcessPaymentSimulationCommandPayload(simulationId, env.payload()));

        byte[] commandBytes = avroSerde.serialize(Topics.CORE_COMMAND, AvroMapper.toAvroCommand(command));
        recordOutbox(command, Topics.CORE_COMMAND, env.requestId(), traceparent, commandBytes);

        LOG.info("Persisted simulation and enqueued Core command requestId={} simulationId={}",
                env.requestId(), simulationId);
    }

    /**
     * Consumes the Core response, persists the terminal state and enqueues the
     * final event toward the API (completed/failed) — again via the outbox.
     */
    @Transactional
    public void handleCoreResponse(EventEnvelope<CorePaymentSimulationResponsePayload> env) {
        CorePaymentSimulationResponsePayload core = env.payload();
        Optional<PaymentSbusMessage> found = messageRepository.findBySimulationId(core.simulationId());
        if (found.isEmpty()) {
            LOG.warn("Core response for unknown simulationId={} (requestId={}) — ignoring",
                    core.simulationId(), env.requestId());
            return;
        }
        PaymentSbusMessage message = found.get();
        if (message.getStatus() == SbusMessageStatus.COMPLETED
                || message.getStatus() == SbusMessageStatus.FAILED) {
            LOG.info("Duplicate Core response ignored requestId={} status={}",
                    message.getRequestId(), message.getStatus());
            return;
        }

        boolean approved = SimulationResult.APPROVED.equalsIgnoreCase(core.status());
        SimulationResult result = new SimulationResult(
                core.simulationId(),
                message.getRequestId(),
                core.status(),
                core.authorizationCode(),
                core.amount(),
                core.currency(),
                core.installments(),
                core.fees(),
                core.settlement(),
                core.errorCode(),
                core.errorMessage());

        message.setStatus(approved ? SbusMessageStatus.COMPLETED : SbusMessageStatus.FAILED);
        message.setErrorCode(core.errorCode());
        message.setErrorMessage(core.errorMessage());
        message.setResult(json.toJson(result));
        messageRepository.update(message);

        String finalType = approved
                ? EventTypes.PAYMENT_SIMULATION_COMPLETED
                : EventTypes.PAYMENT_SIMULATION_FAILED;
        String finalTopic = approved ? Topics.COMPLETED : Topics.FAILED;

        EventEnvelope<SimulationResult> finalEvent = env.deriveAs(finalType, Sources.SBUS, result);
        byte[] finalBytes = approved
                ? avroSerde.serialize(finalTopic, AvroMapper.toAvroCompleted(finalEvent))
                : avroSerde.serialize(finalTopic, AvroMapper.toAvroFailed(finalEvent));
        recordOutbox(finalEvent, finalTopic, message.getRequestId(), null, finalBytes);

        if (message.getCreatedAt() != null) {
            metrics.recordEndToEnd(Duration.between(message.getCreatedAt(), Instant.now()));
        }
        LOG.info("Recorded final event {} requestId={} simulationId={}",
                finalType, message.getRequestId(), core.simulationId());
    }

    private void recordOutbox(EventEnvelope<?> envelope, String topic, String key,
                              String traceparent, byte[] payload) {
        Map<String, String> headers = HeaderMap.from(envelope, traceparent);
        OutboxEvent outbox = new OutboxEvent();
        outbox.setAggregateType(AGGREGATE_TYPE);
        outbox.setAggregateId(envelope.requestId());
        outbox.setEventType(envelope.eventType());
        outbox.setTopic(topic);
        outbox.setKey(key);
        outbox.setPayload(payload);
        outbox.setHeaders(json.toJson(headers));
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setAttempts(0);
        outbox.setNextAttemptAt(Instant.now());
        outboxRepository.save(outbox);
    }
}
