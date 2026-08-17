package com.example.payments.sbus.service;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Sources;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.SimulationResult;
import com.example.payments.sbus.domain.PaymentSbusMessage;
import com.example.payments.sbus.domain.SbusMessageStatus;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import com.example.payments.sbus.support.Json;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task_T11 (AUD-11): unit-level proof of {@code PaymentSimulationService}'s glue when
 * {@code registerReplayInFlight} discovers — inside its own transaction — that the original
 * already went terminal. The caller must re-resolve this exactly like an already-terminal
 * replay ({@code persistReplayFinal}) using the FRESH row handed back, never leave it as if
 * registration had silently succeeded. {@code IdempotencyReplayIT} proves
 * {@code registerReplayInFlight} itself never leaves a stranded PROCESSING row against a real
 * database; this proves the service-level re-resolution that turns that signal into a completed
 * replay actually happens.
 */
class PaymentSimulationServiceUnitTest {

    @Test
    void whenRegisterReplayInFlightFindsTheOriginalAlreadyTerminalTheServiceResolvesItAsATerminalReplay() {
        PaymentSbusMessageRepository messageRepository = mock(PaymentSbusMessageRepository.class);
        PaymentPersistenceService persistence = mock(PaymentPersistenceService.class);
        Json json = mock(Json.class);
        AvroSerde avroSerde = mock(AvroSerde.class);

        String idempotencyKey = "idem-race";
        String originalRequestId = "req-original";
        String replayRequestId = "req-replay";
        String simulationId = "sim-1";

        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("50.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");
        var replayEnv = EventEnvelope.create(EventTypes.PAYMENT_SIMULATION_REQUESTED,
                replayRequestId, UUID.randomUUID().toString(), replayRequestId, "trace", Sources.API, payload);

        PaymentSbusMessage staleOriginal = new PaymentSbusMessage();
        staleOriginal.setRequestId(originalRequestId);
        staleOriginal.setSimulationId(simulationId);
        staleOriginal.setStatus(SbusMessageStatus.PROCESSING);

        PaymentSbusMessage freshTerminalOriginal = new PaymentSbusMessage();
        freshTerminalOriginal.setRequestId(originalRequestId);
        freshTerminalOriginal.setSimulationId(simulationId);
        freshTerminalOriginal.setStatus(SbusMessageStatus.COMPLETED);
        freshTerminalOriginal.setResult("{\"simulationId\":\"sim-1\"}");

        when(messageRepository.findByRequestId(replayRequestId)).thenReturn(Optional.empty());
        // replayEnv carries no tenantId (built via the tenant-less EventEnvelope.create overload),
        // so the service resolves it to the "legacy" fallback (TEN-06 edge case) before calling in.
        when(persistence.findReplayTarget(eq("legacy"), eq(idempotencyKey), eq(replayRequestId), anyString()))
                .thenReturn(Optional.of(staleOriginal));
        when(persistence.registerReplayInFlight(eq(replayEnv), eq(idempotencyKey), isNull(), eq(staleOriginal)))
                .thenReturn(Optional.of(freshTerminalOriginal));
        when(json.fromJson(eq(freshTerminalOriginal.getResult()), eq(SimulationResult.class)))
                .thenReturn(new SimulationResult(simulationId, originalRequestId, SimulationResult.APPROVED,
                        null, new BigDecimal("50.00"), "BRL", 1, null, null, null, null));
        when(json.toJson(any())).thenReturn("{}");
        when(avroSerde.serialize(anyString(), any(SpecificRecord.class))).thenReturn(new byte[]{1});

        PaymentSimulationService service =
                new PaymentSimulationService(messageRepository, persistence, json, avroSerde);

        service.handleRequested(replayEnv, idempotencyKey, null);

        // The stranded-forever bug this replaces would have silently returned here (registration
        // treated as a normal in-flight success) — no persistReplayFinal call, no terminal event,
        // ever, for the replay.
        verify(persistence).persistReplayFinal(eq(replayEnv), eq(idempotencyKey), isNull(), eq(freshTerminalOriginal),
                anyString(), anyString(), any(byte[].class), anyString(), anyString());
    }

    /**
     * task_T34 (OBS-02): the traceparent used for the final event's headers must be the one
     * persisted on the row at ITS OWN ingestion (see {@code PaymentPersistenceService}) — not
     * null, and not anything carried by the Core's response envelope, which is an unrelated trace.
     */
    @Test
    void handleCoreResponseUsesTheTraceparentPersistedAtIngestionForTheFinalEventHeaders() {
        PaymentSbusMessageRepository messageRepository = mock(PaymentSbusMessageRepository.class);
        PaymentPersistenceService persistence = mock(PaymentPersistenceService.class);
        Json json = mock(Json.class);
        AvroSerde avroSerde = mock(AvroSerde.class);

        String simulationId = "sim-trace";
        String requestId = "req-trace";
        String persistedTraceparent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";

        PaymentSbusMessage message = new PaymentSbusMessage();
        message.setRequestId(requestId);
        message.setSimulationId(simulationId);
        message.setCorrelationId("corr-trace");
        message.setTenantId("tenant-a");
        message.setTraceparent(persistedTraceparent);

        when(persistence.findProcessingBySimulationId(simulationId)).thenReturn(List.of(message));
        when(json.toJson(any())).thenReturn("{}");
        when(avroSerde.serialize(anyString(), any(SpecificRecord.class))).thenReturn(new byte[]{1});
        when(persistence.persistFinal(eq(message), anyBoolean(), any(), any(), anyString(), anyString(),
                anyString(), any(byte[].class), anyString())).thenReturn(true);

        PaymentSimulationService service =
                new PaymentSimulationService(messageRepository, persistence, json, avroSerde);

        var corePayload = new CorePaymentSimulationResponsePayload(simulationId, SimulationResult.APPROVED,
                "654321", new BigDecimal("50.00"), "BRL", 1, null, null, null, null);
        var coreEnv = EventEnvelope.create(EventTypes.CORE_PAYMENT_SIMULATION_RESPONSE, requestId,
                "corr-trace", "cause-trace", "trace-unrelated", Sources.CORE, corePayload);

        service.handleCoreResponse(coreEnv);

        ArgumentCaptor<Object> jsonArgs = ArgumentCaptor.forClass(Object.class);
        verify(json, atLeastOnce()).toJson(jsonArgs.capture());
        boolean headerMapCarriedPersistedTraceparent = jsonArgs.getAllValues().stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(headerMap -> persistedTraceparent.equals(headerMap.get(Headers.TRACEPARENT)));

        assertTrue(headerMapCarriedPersistedTraceparent,
                "the final event's header map must carry the traceparent persisted at ingestion, "
                        + "not null and not the Core response's own trace");
    }
}
