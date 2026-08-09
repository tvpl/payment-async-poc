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

    @Transactional
    public void persistRequested(EventEnvelope<PaymentSimulationRequestPayload> env,
                                 String idempotencyKey, String simulationId,
                                 String eventType, String topic, byte[] commandBytes, String headers) {
        // Authoritative idempotency inside the tx (request_id UNIQUE is the backstop).
        if (messageRepository.findByRequestId(env.requestId()).isPresent()) {
            return;
        }
        if (idempotencyKey != null && idempotencyRepository.existsByIdempotencyKey(idempotencyKey)) {
            LOG.info("Duplicate idempotencyKey={} ignored requestId={}", idempotencyKey, env.requestId());
            return;
        }

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

        saveOutbox(env.requestId(), eventType, topic, env.requestId(), commandBytes, headers);
        LOG.info("Persisted simulation and enqueued Core command requestId={} simulationId={}",
                env.requestId(), simulationId);
    }

    @Transactional
    public void persistFinal(String simulationId, boolean approved, String errorCode,
                             String errorMessage, String resultJson, String eventType,
                             String topic, byte[] finalBytes, String headers) {
        PaymentSbusMessage message = messageRepository.findBySimulationId(simulationId).orElse(null);
        if (message == null || isTerminal(message.getStatus())) {
            return; // race: another delivery already finalized it
        }
        message.setStatus(approved ? SbusMessageStatus.COMPLETED : SbusMessageStatus.FAILED);
        message.setErrorCode(errorCode);
        message.setErrorMessage(errorMessage);
        message.setResult(resultJson);
        messageRepository.update(message);

        saveOutbox(message.getRequestId(), eventType, topic, message.getRequestId(), finalBytes, headers);

        if (message.getCreatedAt() != null) {
            metrics.recordEndToEnd(Duration.between(message.getCreatedAt(), Instant.now()));
        }
        LOG.info("Recorded final event {} requestId={} simulationId={}",
                eventType, message.getRequestId(), simulationId);
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
