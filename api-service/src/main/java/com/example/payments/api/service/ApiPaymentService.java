package com.example.payments.api.service;

import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.kafka.PaymentRequestProducer;
import com.example.payments.api.metrics.ApiMetrics;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.serde.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the synchronous-over-asynchronous flow: persist PENDING, publish to
 * Kafka, then block (on a virtual thread) for the correlated response up to a
 * timeout, returning either the result or a 202-style "still processing" outcome.
 */
@Singleton
public class ApiPaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(ApiPaymentService.class);

    private final RedisStatusStore store;
    private final ResponseCoordinator coordinator;
    private final PaymentRequestProducer producer;
    private final ObjectMapper objectMapper;
    private final ApiMetrics metrics;

    public ApiPaymentService(RedisStatusStore store,
                             ResponseCoordinator coordinator,
                             PaymentRequestProducer producer,
                             ObjectMapper objectMapper,
                             ApiMetrics metrics) {
        this.store = store;
        this.coordinator = coordinator;
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /** Outcome of a submit: the current entry, whether we timed out, whether it was a replay. */
    public record SubmitResult(StatusEntry entry, boolean timedOut, boolean duplicate) {
    }

    public SubmitResult submit(PaymentSimulationRequest request, String idempotencyKeyHeader) {
        metrics.recordRequest();

        String idempotencyKey = (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank())
                ? UUID.randomUUID().toString()
                : idempotencyKeyHeader;
        String requestId = UUID.randomUUID().toString();

        // Idempotency: first writer wins the key; later identical requests replay it.
        Optional<String> owner = store.reserveIdempotency(idempotencyKey, requestId);
        if (owner.isPresent() && !owner.get().equals(requestId)) {
            String originalId = owner.get();
            StatusEntry entry = store.get(originalId)
                    .orElse(new StatusEntry(originalId, SimulationStatus.PROCESSING, null));
            LOG.info("Idempotent replay key={} -> requestId={} status={}",
                    idempotencyKey, originalId, entry.status());
            return new SubmitResult(entry, !isTerminal(entry.status()), true);
        }

        String correlationId = UUID.randomUUID().toString();
        String traceId = currentTraceId();
        MDC.put("requestId", requestId);
        MDC.put("correlationId", correlationId);
        MDC.put("traceId", traceId);

        store.save(new StatusEntry(requestId, SimulationStatus.PENDING, null));
        CompletableFuture<StatusEntry> future = coordinator.register(requestId);

        EventEnvelope<PaymentSimulationRequestPayload> envelope = EventEnvelope.create(
                EventTypes.PAYMENT_SIMULATION_REQUESTED,
                requestId, correlationId, requestId, traceId, Sources.API,
                request.toPayload());

        try {
            producer.send(requestId, requestId, correlationId, idempotencyKey,
                    objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            coordinator.unregister(requestId);
            throw new IllegalStateException("Failed to publish PaymentSimulationRequested", e);
        }
        store.save(new StatusEntry(requestId, SimulationStatus.SENT_TO_SBUS, null));
        LOG.info("Published PaymentSimulationRequested requestId={}", requestId);

        long start = System.nanoTime();
        Optional<StatusEntry> result = coordinator.await(requestId, future);
        metrics.recordWait(Duration.ofNanos(System.nanoTime() - start));
        MDC.clear();

        if (result.isPresent()) {
            return new SubmitResult(result.get(), false, false);
        }
        metrics.recordTimeout();
        StatusEntry current = store.get(requestId)
                .orElse(new StatusEntry(requestId, SimulationStatus.SENT_TO_SBUS, null));
        return new SubmitResult(current, true, false);
    }

    public Optional<StatusEntry> getStatus(String requestId) {
        return store.get(requestId);
    }

    private static boolean isTerminal(SimulationStatus status) {
        return status == SimulationStatus.COMPLETED || status == SimulationStatus.FAILED;
    }

    private static String currentTraceId() {
        var ctx = Span.current().getSpanContext();
        if (ctx.isValid()) {
            return ctx.getTraceId();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
