package com.example.payments.api.service;

import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.client.SbusStatusClient;
import com.example.payments.api.client.SbusStatusResponse;
import com.example.payments.api.error.PublishFailedException;
import com.example.payments.api.kafka.PaymentRequestProducer;
import com.example.payments.api.metrics.ApiMetrics;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.SimulationStatus;
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
    private final AvroSerde avroSerde;
    private final ApiMetrics metrics;
    private final SbusStatusClient sbusStatusClient;

    public ApiPaymentService(RedisStatusStore store,
                             ResponseCoordinator coordinator,
                             PaymentRequestProducer producer,
                             AvroSerde avroSerde,
                             ApiMetrics metrics,
                             SbusStatusClient sbusStatusClient) {
        this.store = store;
        this.coordinator = coordinator;
        this.producer = producer;
        this.avroSerde = avroSerde;
        this.metrics = metrics;
        this.sbusStatusClient = sbusStatusClient;
    }

    /** Outcome of a submit: the current entry, whether we timed out, whether it was a replay. */
    public record SubmitResult(StatusEntry entry, boolean timedOut, boolean duplicate) {
    }

    public SubmitResult submit(PaymentSimulationRequest request, String idempotencyKeyHeader) {
        metrics.recordRequest(request.paymentMethod());

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
            byte[] bytes = avroSerde.serialize(Topics.REQUESTED, AvroMapper.toAvroRequested(envelope));
            producer.send(requestId, requestId, correlationId, idempotencyKey, bytes);
        } catch (Exception e) {
            coordinator.unregister(requestId);
            throw new PublishFailedException("Failed to publish PaymentSimulationRequested", e);
        }
        store.save(new StatusEntry(requestId, SimulationStatus.SENT_TO_SBUS, null));
        LOG.info("Published PaymentSimulationRequested requestId={}", requestId);

        // Read-after-register: a very fast response (or a replay) may have completed in
        // Redis before our waiter was wired up; pick it up so we don't wait needlessly.
        coordinator.completeFromStore(requestId);

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

    /**
     * Status lookup with durable fallback: Redis first; if absent or not yet terminal,
     * consult the SBUS (Postgres-backed) so a finished result is never lost when the
     * Redis entry expired or was never written by this set of instances.
     */
    public Optional<StatusEntry> getStatus(String requestId) {
        Optional<StatusEntry> local = store.get(requestId);
        if (local.isPresent() && isTerminal(local.get().status())) {
            return local;
        }
        Optional<StatusEntry> durable = fromSbus(requestId);
        if (durable.isPresent()) {
            return durable;
        }
        return local;
    }

    private Optional<StatusEntry> fromSbus(String requestId) {
        try {
            return sbusStatusClient.getStatus(requestId).map(this::toEntry);
        } catch (Exception e) {
            LOG.debug("SBUS status fallback unavailable for {}: {}", requestId, e.getMessage());
            return Optional.empty();
        }
    }

    private StatusEntry toEntry(SbusStatusResponse r) {
        SimulationStatus status;
        try {
            status = SimulationStatus.valueOf(r.status());
        } catch (IllegalArgumentException e) {
            status = SimulationStatus.PROCESSING;
        }
        return new StatusEntry(r.requestId(), status, r.result());
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
