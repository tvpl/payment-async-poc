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

        String simulationId = UUID.randomUUID().toString();
        EventEnvelope<ProcessPaymentSimulationCommandPayload> command = env.deriveAs(
                EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND,
                Sources.SBUS,
                new ProcessPaymentSimulationCommandPayload(simulationId, env.payload()));

        // Avro serialization OUTSIDE the transaction.
        byte[] commandBytes = avroSerde.serialize(Topics.CORE_COMMAND, AvroMapper.toAvroCommand(command));
        String headers = json.toJson(HeaderMap.from(command, traceparent));

        persistence.persistRequested(env, idempotencyKey, simulationId,
                EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND, Topics.CORE_COMMAND, commandBytes, headers);
    }

    public void handleCoreResponse(EventEnvelope<CorePaymentSimulationResponsePayload> env) {
        CorePaymentSimulationResponsePayload core = env.payload();
        Optional<PaymentSbusMessage> found = messageRepository.findBySimulationId(core.simulationId());
        if (found.isEmpty()) {
            LOG.warn("Core response for unknown simulationId={} (requestId={}) — ignoring",
                    core.simulationId(), env.requestId());
            return;
        }
        PaymentSbusMessage message = found.get();
        if (isTerminal(message.getStatus())) {
            LOG.info("Duplicate Core response ignored requestId={} status={}",
                    message.getRequestId(), message.getStatus());
            return;
        }

        boolean approved = SimulationResult.APPROVED.equalsIgnoreCase(core.status());
        SimulationResult result = new SimulationResult(
                core.simulationId(), message.getRequestId(), core.status(), core.authorizationCode(),
                core.amount(), core.currency(), core.installments(), core.fees(), core.settlement(),
                core.errorCode(), core.errorMessage());

        String finalType = approved
                ? EventTypes.PAYMENT_SIMULATION_COMPLETED
                : EventTypes.PAYMENT_SIMULATION_FAILED;
        String finalTopic = approved ? Topics.COMPLETED : Topics.FAILED;
        EventEnvelope<SimulationResult> finalEvent = env.deriveAs(finalType, Sources.SBUS, result);

        // Avro serialization OUTSIDE the transaction.
        byte[] finalBytes = approved
                ? avroSerde.serialize(finalTopic, AvroMapper.toAvroCompleted(finalEvent))
                : avroSerde.serialize(finalTopic, AvroMapper.toAvroFailed(finalEvent));
        String headers = json.toJson(HeaderMap.from(finalEvent, null));

        persistence.persistFinal(core.simulationId(), approved, core.errorCode(), core.errorMessage(),
                json.toJson(result), finalType, finalTopic, finalBytes, headers);
    }

    private static boolean isTerminal(SbusMessageStatus status) {
        return status == SbusMessageStatus.COMPLETED || status == SbusMessageStatus.FAILED;
    }
}
