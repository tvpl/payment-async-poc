package com.example.payments.sbus.outbox;

import com.example.payments.common.events.Topics;
import com.example.payments.common.ratelimit.RedisRateLimiter;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.kafka.KafkaPublisher;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.support.Json;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates outbox publication. Claims a batch in a short transaction
 * ({@link OutboxClaimService#claimBatch()}), publishes to Kafka <em>outside</em> any
 * transaction (no locks during I/O), then marks the whole successful batch PUBLISHED in a
 * single statement. Throughput toward the Core is capped by a <strong>distributed</strong>
 * {@link RedisRateLimiter} on {@code core.command} — a global guard across SBUS instances.
 */
@Singleton
public class OutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxClaimService claimService;
    private final KafkaPublisher publisher;
    private final SbusMetrics metrics;
    private final Json json;
    private final RedisRateLimiter coreCommandLimiter;

    public OutboxDispatcher(OutboxClaimService claimService,
                            KafkaPublisher publisher,
                            SbusMetrics metrics,
                            Json json,
                            @Named("core-command") RedisRateLimiter coreCommandLimiter) {
        this.claimService = claimService;
        this.publisher = publisher;
        this.metrics = metrics;
        this.json = json;
        this.coreCommandLimiter = coreCommandLimiter;
    }

    public int dispatchBatch() {
        List<OutboxEvent> batch = claimService.claimBatch();
        List<Long> published = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            // Backpressure to the Core: if the limiter denies, release the row (PENDING)
            // and retry on the next poll. Other topics flow freely.
            if (Topics.CORE_COMMAND.equals(event.getTopic()) && !coreCommandLimiter.tryAcquire()) {
                claimService.release(event, Instant.now().plusMillis(200));
                continue;
            }
            if (publish(event)) {
                published.add(event.getId());
            }
        }
        claimService.markPublishedBatch(published);
        if (!published.isEmpty()) {
            metrics.recordPublished(published.size());
        }
        return published.size();
    }

    private boolean publish(OutboxEvent event) {
        try {
            publisher.send(event.getTopic(), event.getKey(), event.getPayload(), parseHeaders(event));
            return true;
        } catch (Exception e) {
            metrics.recordPublishFailure();
            boolean dead = claimService.markFailure(event, e.getMessage());
            if (dead) {
                routeToDlq(event, e);
                LOG.error("Outbox event {} exhausted attempts -> DLQ", event.getId(), e);
            } else {
                LOG.warn("Outbox publish failed event={} (will retry)", event.getId(), e);
            }
            return false;
        }
    }

    private void routeToDlq(OutboxEvent event, Exception e) {
        try {
            Map<String, String> headers = parseHeaders(event);
            headers.put("x-dlq-origin-topic", event.getTopic());
            headers.put("x-dlq-reason", String.valueOf(e.getMessage()));
            publisher.send(Topics.DLQ, event.getKey(), event.getPayload(), headers);
            metrics.recordDlq();
        } catch (Exception dlqEx) {
            LOG.error("Failed to route outbox event {} to DLQ", event.getId(), dlqEx);
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
