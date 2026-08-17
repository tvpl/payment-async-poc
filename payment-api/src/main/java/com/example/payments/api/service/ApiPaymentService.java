package com.example.payments.api.service;

import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.client.SbusStatusResponse;
import com.example.payments.api.coordination.SbusStatusGateway;
import com.example.payments.api.error.IdempotencyConflictException;
import com.example.payments.api.error.PublishFailedException;
import com.example.payments.api.error.StoreUnavailableException;
import com.example.payments.api.idempotency.IdempotencyFingerprint;
import com.example.payments.api.idempotency.IdempotencyOutcome;
import com.example.payments.api.idempotency.PublishState;
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
import io.micronaut.core.annotation.Nullable;
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
    private final SbusStatusGateway sbusStatusGateway;

    public ApiPaymentService(RedisStatusStore store,
                             ResponseCoordinator coordinator,
                             PaymentRequestProducer producer,
                             AvroSerde avroSerde,
                             ApiMetrics metrics,
                             SbusStatusGateway sbusStatusGateway) {
        this.store = store;
        this.coordinator = coordinator;
        this.producer = producer;
        this.avroSerde = avroSerde;
        this.metrics = metrics;
        this.sbusStatusGateway = sbusStatusGateway;
    }

    /**
     * Outcome of a submit: the current entry, whether we timed out, whether it was a replay, and
     * the correlationId this request resolved to (OBS-03) - adopted from a valid inbound header
     * or freshly generated, always echoed back in the response.
     */
    public record SubmitResult(StatusEntry entry, boolean timedOut, boolean duplicate, String correlationId) {
    }

    /** Convenience overload for callers with no inbound correlation-id header. */
    public SubmitResult submit(PaymentSimulationRequest request, String idempotencyKeyHeader, String tenantId) {
        return submit(request, idempotencyKeyHeader, tenantId, null);
    }

    /**
     * @param correlationIdHeader the raw {@code x-correlation-id} header value, or {@code null}/blank
     *                            if absent. OBS-03: a value matching {@link CorrelationIdValidation}
     *                            is adopted as-is; anything else (absent, malformed) is silently
     *                            ignored and a new id is generated - never a reason to reject the
     *                            request.
     */
    public SubmitResult submit(PaymentSimulationRequest request, String idempotencyKeyHeader, String tenantId,
                               @Nullable String correlationIdHeader) {
        metrics.recordRequest(request.paymentMethod());

        String correlationId = CorrelationIdValidation.isValid(correlationIdHeader)
                ? correlationIdHeader
                : UUID.randomUUID().toString();
        String idempotencyKey = (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank())
                ? UUID.randomUUID().toString()
                : idempotencyKeyHeader;
        // Idempotency: first writer wins the key + canonical fingerprint atomically, scoped to the
        // tenant; a later request with the same key replays it only if the payload also matches and
        // the tenant is the same owner (PAY-01/PAY-02, TEN-04/TEN-05).
        String fingerprint = IdempotencyFingerprint.of(request);
        String requestId = UUID.randomUUID().toString();
        IdempotencyOutcome outcome = store.reserve(tenantId, idempotencyKey, requestId, fingerprint);

        if (outcome instanceof IdempotencyOutcome.Conflict conflict) {
            LOG.info("Idempotency conflict tenant={} key={} originalRequestId={}",
                    tenantId, idempotencyKey, conflict.requestId());
            throw new IdempotencyConflictException(idempotencyKey, conflict.requestId());
        }
        if (outcome instanceof IdempotencyOutcome.Replay replay) {
            return replay(idempotencyKey, replay.requestId(), correlationId);
        }
        if (outcome instanceof IdempotencyOutcome.ResumePublish resume) {
            // A previous attempt on this key never confirmed its publish. Recover it under the
            // same identity rather than reporting progress that never happened (PAY-03).
            requestId = resume.requestId();
            LOG.info("Resuming unpublished request tenant={} key={} requestId={}", tenantId, idempotencyKey, requestId);
        }
        return publishAndAwait(request, tenantId, idempotencyKey, fingerprint, requestId, correlationId);
    }

    /** Replays a confirmed identity. A status we do not have is never reported as progress. */
    private SubmitResult replay(String idempotencyKey, String requestId, String correlationId) {
        StatusEntry entry = store.get(requestId)
                .orElseGet(() -> new StatusEntry(requestId, SimulationStatus.TIMEOUT, null));
        LOG.info("Idempotent replay key={} -> requestId={} status={}",
                idempotencyKey, requestId, entry.status());
        return new SubmitResult(entry, !isTerminal(entry.status()), true, correlationId);
    }

    private SubmitResult publishAndAwait(PaymentSimulationRequest request,
                                         String tenantId,
                                         String idempotencyKey,
                                         String fingerprint,
                                         String requestId,
                                         String correlationId) {
        String traceId = currentTraceId();
        MDC.put("requestId", requestId);
        MDC.put("correlationId", correlationId);
        // The first event in the chain: causationId is its own requestId (EventEnvelope's own
        // javadoc convention), matching the envelope built below (EventEnvelope.create(...,
        // requestId, correlationId, requestId, traceId, ...) — requestId is also the 3rd,
        // causationId, argument).
        MDC.put("causationId", requestId);
        MDC.put("traceId", traceId);
        MDC.put("tenantId", tenantId);
        // Every exit from here on — result, timeout, interruption, shutdown or publish failure —
        // leaves the thread's MDC clean; a request thread is reused (PAY-10).
        try {
            return publishAndAwaitLogged(request, tenantId, idempotencyKey, fingerprint, requestId, correlationId, traceId);
        } finally {
            MDC.clear();
        }
    }

    private SubmitResult publishAndAwaitLogged(PaymentSimulationRequest request,
                                               String tenantId,
                                               String idempotencyKey,
                                               String fingerprint,
                                               String requestId,
                                               String correlationId,
                                               String traceId) {
        store.save(new StatusEntry(requestId, SimulationStatus.PENDING, null));
        CompletableFuture<StatusEntry> future = coordinator.register(requestId);
        // coordinator.await() below always unregisters the waiter itself (in its own finally),
        // on every one of its exit paths. Everything between register() and that call is not
        // covered by that guarantee, though: markPublishState/save/completeFromStore can each
        // throw, and an exception there used to leak the waiter forever (AUD-06). reachedAwait
        // tracks whether control actually made it to await(); if not, this finally is the only
        // thing that ever cleans the registration up.
        boolean reachedAwait = false;
        try {
            EventEnvelope<PaymentSimulationRequestPayload> envelope = EventEnvelope.create(
                    EventTypes.PAYMENT_SIMULATION_REQUESTED,
                    requestId, correlationId, requestId, traceId, Sources.API, tenantId,
                    request.toPayload());

            try {
                byte[] bytes = avroSerde.serialize(Topics.REQUESTED, AvroMapper.toAvroRequested(envelope));
                producer.send(requestId, requestId, correlationId, idempotencyKey, tenantId, bytes);
            } catch (Exception e) {
                // Keep the identity, mark it unpublished: the caller gets an honest failure and a
                // retry with the same key resumes this requestId instead of waiting out an orphan.
                store.markPublishState(tenantId, idempotencyKey, requestId, fingerprint, PublishState.PUBLISH_FAILED);
                throw new PublishFailedException("Failed to publish PaymentSimulationRequested", e);
            }
            // Broker acknowledged. A crash in this window republishes the same requestId on retry,
            // which downstream consumers absorb without changing a chosen outcome (PAY-06).
            store.markPublishState(tenantId, idempotencyKey, requestId, fingerprint, PublishState.PUBLISHED);
            store.save(new StatusEntry(requestId, SimulationStatus.SENT_TO_SBUS, null));
            LOG.info("Published PaymentSimulationRequested requestId={}", requestId);

            // Read-after-register: a very fast response (or a replay) may have completed in
            // Redis before our waiter was wired up; pick it up so we don't wait needlessly.
            coordinator.completeFromStore(requestId);

            long start = System.nanoTime();
            reachedAwait = true;
            Optional<StatusEntry> result = coordinator.await(requestId, future);
            metrics.recordWait(Duration.ofNanos(System.nanoTime() - start));

            if (result.isPresent()) {
                return new SubmitResult(result.get(), false, false, correlationId);
            }
            metrics.recordTimeout();
            StatusEntry current = store.get(requestId)
                    .orElse(new StatusEntry(requestId, SimulationStatus.SENT_TO_SBUS, null));
            return new SubmitResult(current, true, false, correlationId);
        } finally {
            if (!reachedAwait) {
                coordinator.unregister(requestId);
            }
        }
    }

    /**
     * Status lookup with durable fallback: Redis first; if absent or not yet terminal,
     * consult the SBUS (Postgres-backed) so a finished result is never lost when the
     * Redis entry expired or was never written by this set of instances.
     *
     * <p>A Redis outage does not, by itself, turn into an error here: the SBUS fallback is
     * still consulted, so a caller polling for a request whose Redis entry can't be read gets
     * the durable answer instead of losing it to an infrastructure blip. Only when SBUS also
     * has nothing (unknown request, or its own circuit is open) does the original Redis
     * failure surface — as {@link com.example.payments.api.error.StoreUnavailableException},
     * never as an empty result that the caller would read as "no such request" (RES-02/RES-03).
     */
    public Optional<StatusEntry> getStatus(String requestId) {
        Optional<StatusEntry> local = Optional.empty();
        StoreUnavailableException storeFailure = null;
        try {
            local = store.get(requestId);
        } catch (StoreUnavailableException e) {
            storeFailure = e;
        }
        if (local.isPresent() && isTerminal(local.get().status())) {
            return local;
        }
        Optional<StatusEntry> durable = fromSbus(requestId);
        if (durable.isPresent()) {
            return durable;
        }
        if (storeFailure != null) {
            throw storeFailure;
        }
        return local;
    }

    private Optional<StatusEntry> fromSbus(String requestId) {
        return sbusStatusGateway.getStatus(requestId).map(this::toEntry);
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
