package com.example.payments.sbus.outbox;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.ratelimit.RedisRateLimiter;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.kafka.KafkaPublisher;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.support.Json;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates outbox publication. Claims a batch in a short transaction
 * ({@link OutboxClaimService#claimBatch()}), publishes to Kafka <em>outside</em> any
 * transaction (no locks during I/O), and marks each event PUBLISHED individually right after
 * its own send succeeds — not accumulated and marked only once the whole batch finishes. That
 * matters because nothing bounds one batch's wall time against the claim lease
 * ({@code OutboxProperties#lease}): if it expires partway through (a slow Kafka leader election,
 * a Redis hiccup on the rate limiter below), a batch-wide mark would leave every already-sent
 * event unrecorded — and therefore reclaimable by the reaper and republished, duplicating work
 * the Core already did. Marking per item means only events that never finished sending are ever
 * at risk of that, which is the reaper's actual job. Throughput toward the Core is capped by a
 * <strong>distributed</strong> {@link RedisRateLimiter} on {@code core.command} — a global guard
 * across SBUS instances; a Redis outage there degrades that one topic's throttling, it does not
 * abort the rest of the batch (see {@link #tryAcquireCoreCommand()}).
 */
@Singleton
public class OutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);

    /** OBS-02: reads/writes the {@code traceparent} header on the plain string-map carrier the
     * outbox already persists (see {@link #parseHeaders}) — never touches Kafka record headers
     * directly, since the span must exist (and its OWN traceparent be computed) before the record
     * is built. */
    private static final TextMapGetter<Map<String, String>> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };
    private static final TextMapSetter<Map<String, String>> HEADER_SETTER = Map::put;

    private final OutboxClaimService claimService;
    private final KafkaPublisher publisher;
    private final SbusMetrics metrics;
    private final Json json;
    private final RedisRateLimiter coreCommandLimiter;
    private final OutboxPublicationLock publicationLock;
    private final Tracer tracer;

    // RES-06: set by @PreDestroy so an ordered shutdown stops claiming new batches; currentBatch
    // tracks the batch this instance most recently claimed and has not fully processed yet, so
    // shutdown() can release whatever is still unpublished in it without incrementing attempts.
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private volatile List<OutboxEvent> currentBatch = List.of();

    public OutboxDispatcher(OutboxClaimService claimService,
                            KafkaPublisher publisher,
                            SbusMetrics metrics,
                            Json json,
                            @Named("core-command") RedisRateLimiter coreCommandLimiter,
                            OutboxPublicationLock publicationLock,
                            Tracer tracer) {
        this.claimService = claimService;
        this.publisher = publisher;
        this.metrics = metrics;
        this.json = json;
        this.coreCommandLimiter = coreCommandLimiter;
        this.publicationLock = publicationLock;
        this.tracer = tracer;
    }

    public int dispatchBatch() {
        if (shuttingDown.get()) {
            return 0;
        }
        List<OutboxEvent> batch = claimService.claimBatch();
        currentBatch = batch;
        int publishedCount = 0;
        try {
            for (int i = 0; i < batch.size(); i++) {
                if (shuttingDown.get()) {
                    // RES-06: an ordered shutdown fired mid-batch — release whatever this
                    // instance still has claimed but has not published, cleanly (PENDING,
                    // attempts unchanged), instead of leaving it IN_PROGRESS for the reaper's
                    // lease-based (attempts-incrementing) recovery to eventually find.
                    releaseUnprocessed(batch.subList(i, batch.size()));
                    break;
                }
                OutboxEvent event = batch.get(i);
                // Backpressure to the Core: if the limiter denies (or can't be reached — see
                // tryAcquireCoreCommand), release the row (PENDING) and retry on the next poll.
                // Other topics flow freely: a Redis outage on this one distributed counter must
                // not stop payment.simulation.completed/failed/DLQ, which need no rate limiting.
                if (Topics.CORE_COMMAND.equals(event.getTopic()) && !tryAcquireCoreCommand()) {
                    claimService.release(event, Instant.now().plusMillis(200));
                    continue;
                }
                if (publish(event)) {
                    // Marked right away, not batched to the end of the loop — see class javadoc.
                    if (claimService.markPublished(event)) {
                        publishedCount++;
                        metrics.recordPublished(1);
                        if (Topics.DLQ.equals(event.getTopic())) {
                            metrics.recordDlq();
                        }
                    } else {
                        LOG.warn("Outbox event {} sent to Kafka but its claim was stale when marking "
                                + "published — a duplicate PUBLISHED mark from a previous owner is "
                                + "expected to have already recorded it", event.getId());
                    }
                }
                // AUD-07: renew the lease of the rows this batch still hasn't gotten to, right after
                // this row's own turn — never accumulated to the end of the loop. A slow batch (one
                // row taking a long time) must not let its OWN lease expire out from under the rows
                // still waiting; without this, the reaper could reclaim — and a later poll could
                // republish — a row this dispatcher is still actively about to send.
                List<OutboxEvent> remaining = batch.subList(i + 1, batch.size());
                if (!remaining.isEmpty() && !claimService.renewRemaining(remaining)) {
                    // A genuinely lost fence: something else (the reaper, on an already-expired
                    // lease) reclaimed at least one remaining row out from under this batch.
                    // Continuing would risk double-publishing it — abort the rest of the batch; the
                    // reaper's normal recovery path (attempts + backoff, see OutboxReaper) picks up
                    // whatever is left.
                    metrics.recordPublishFailure();
                    LOG.error("Lost the outbox claim lease while renewing {} remaining row(s) "
                            + "mid-batch — aborting the rest of this batch", remaining.size());
                    break;
                }
            }
        } finally {
            currentBatch = List.of();
        }
        return publishedCount;
    }

    /**
     * RES-06: stops claiming new batches and releases whatever this instance's most recent batch
     * still had claimed but unpublished, without incrementing {@code attempts} — {@link
     * OutboxClaimService#release} is the same fenced, immediate reset to PENDING the throttle
     * path already uses, so a row released here is never IN_PROGRESS for the reaper to find (a
     * clean release and a crash are distinguishable exactly by that: a crash leaves the row
     * IN_PROGRESS until the lease expires, and only then does the reaper — a genuinely different
     * path — increment attempts).
     */
    @PreDestroy
    public void shutdown() {
        shuttingDown.set(true);
        releaseUnprocessed(currentBatch);
    }

    private void releaseUnprocessed(List<OutboxEvent> rows) {
        for (OutboxEvent event : rows) {
            try {
                claimService.release(event, Instant.now());
            } catch (Exception e) {
                LOG.warn("Failed to cleanly release outbox event {} during shutdown", event.getId(), e);
            }
        }
    }

    /**
     * Wraps the distributed limiter so a Redis outage degrades to "deny this core.command row,
     * retry next poll" instead of throwing out of {@link #dispatchBatch()} — which used to abort
     * the whole batch, leaving every later row (including topics that need no rate limiting at
     * all) untried until the next poll, and leaving every already-published row in this batch
     * unmarked (see class javadoc). Fails closed per item, exactly like a real denial: this
     * limiter exists to protect the Core from bursts, so an unreachable Redis must not let
     * core.command flow uncounted.
     */
    private boolean tryAcquireCoreCommand() {
        try {
            return coreCommandLimiter.tryAcquire();
        } catch (Exception e) {
            LOG.debug("core-command rate limiter unavailable, denying this row for now: {}", e.getMessage());
            return false;
        }
    }

    private boolean publish(OutboxEvent event) {
        try {
            var result = publicationLock.executeIfAcquired(event.getId(), () -> {
                publishWithSpan(event);
                return Boolean.TRUE;
            });
            if (result.isEmpty()) {
                claimService.release(event, Instant.now().plusMillis(200));
                LOG.debug("Deferred outbox event {} because its former owner is still publishing", event.getId());
                return false;
            }
            return true;
        } catch (Exception e) {
            metrics.recordPublishFailure();
            Map<String, String> headers = parseHeaders(event);
            headers.put("x-dlq-origin-topic",
                    headers.getOrDefault("x-dlq-origin-topic", event.getTopic()));
            headers.put("x-dlq-stage", "outbox-publish");
            headers.put("x-dlq-reason", String.valueOf(e.getMessage()));
            var disposition = claimService.markFailure(event, e.getMessage(), json.toJson(headers));
            if (disposition == OutboxClaimService.FailureDisposition.STALE_CLAIM) {
                LOG.warn("Ignored failure from stale outbox claim event={}", event.getId());
            } else if (disposition == OutboxClaimService.FailureDisposition.DLQ_PENDING) {
                LOG.error("Outbox event {} remains recoverable as DLQ_PENDING", event.getId(), e);
            } else {
                LOG.warn("Outbox publish failed event={} (will retry)", event.getId(), e);
            }
            return false;
        }
    }

    /**
     * OBS-02: publish gets its OWN span ("outbox publish") rather than resuming the ingestion
     * trace as a parent — a row can sit in the outbox for anywhere from milliseconds to minutes
     * (backoff, lease waits, a slow batch ahead of it), and chaining it as a child would stretch
     * the ingestion span's own duration to match however long that took. Instead this span links
     * back to the ingestion context persisted on the row's own headers (if any — see {@code
     * HeaderMap.from}), and stamps its OWN {@code traceparent} onto the record before sending, so
     * the event a consumer receives always carries a fresh, currently-valid context rooted at the
     * moment of publish, with a link an OTel backend can use to pivot back to the original request.
     */
    private void publishWithSpan(OutboxEvent event) {
        Map<String, String> headers = parseHeaders(event);
        Context ingestionContext = W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), headers, HEADER_GETTER);
        Span ingestionSpan = Span.fromContextOrNull(ingestionContext);

        SpanBuilder spanBuilder = tracer.spanBuilder("outbox publish").setSpanKind(SpanKind.PRODUCER);
        if (ingestionSpan != null && ingestionSpan.getSpanContext().isValid()) {
            spanBuilder.addLink(ingestionSpan.getSpanContext());
        }
        Span span = spanBuilder.startSpan();
        try (Scope scope = span.makeCurrent()) {
            W3CTraceContextPropagator.getInstance().inject(Context.current(), headers, HEADER_SETTER);
            publisher.send(event.getTopic(), event.getKey(), event.getPayload(), headers);
        } finally {
            span.end();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaders(OutboxEvent event) {
        if (event.getHeaders() == null || event.getHeaders().isBlank()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(json.fromJson(event.getHeaders(), Map.class));
    }
}
