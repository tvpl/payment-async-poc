package com.example.payments.sbus.outbox;

import com.example.payments.common.events.Topics;
import com.example.payments.sbus.ratelimit.RedisRateLimiter;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.kafka.KafkaPublisher;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.support.Json;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final OutboxClaimService claimService;
    private final KafkaPublisher publisher;
    private final SbusMetrics metrics;
    private final Json json;
    private final RedisRateLimiter coreCommandLimiter;
    private final OutboxPublicationLock publicationLock;

    public OutboxDispatcher(OutboxClaimService claimService,
                            KafkaPublisher publisher,
                            SbusMetrics metrics,
                            Json json,
                            @Named("core-command") RedisRateLimiter coreCommandLimiter,
                            OutboxPublicationLock publicationLock) {
        this.claimService = claimService;
        this.publisher = publisher;
        this.metrics = metrics;
        this.json = json;
        this.coreCommandLimiter = coreCommandLimiter;
        this.publicationLock = publicationLock;
    }

    public int dispatchBatch() {
        List<OutboxEvent> batch = claimService.claimBatch();
        int publishedCount = 0;
        for (OutboxEvent event : batch) {
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
        }
        return publishedCount;
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
                publisher.send(event.getTopic(), event.getKey(), event.getPayload(), parseHeaders(event));
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

    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaders(OutboxEvent event) {
        if (event.getHeaders() == null || event.getHeaders().isBlank()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(json.fromJson(event.getHeaders(), Map.class));
    }
}
