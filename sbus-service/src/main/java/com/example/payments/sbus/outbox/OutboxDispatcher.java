package com.example.payments.sbus.outbox;

import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.OutboxProperties;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.kafka.KafkaPublisher;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.support.Json;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claims a batch of due outbox rows ({@code FOR UPDATE SKIP LOCKED}) and publishes
 * them to Kafka, replaying the exact technical headers captured at ingest. On
 * failure it applies exponential backoff; after {@code maxAttempts} it routes the
 * message to the DLQ and marks the row FAILED.
 *
 * <p>Throughput toward the Core is capped by a Resilience4j rate limiter on the
 * {@code core.command} topic — this is what protects a slower Core from bursts.
 */
@Singleton
public class OutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxEventRepository repository;
    private final KafkaPublisher publisher;
    private final OutboxProperties properties;
    private final SbusMetrics metrics;
    private final Json json;
    private final RateLimiter coreCommandLimiter;

    public OutboxDispatcher(OutboxEventRepository repository,
                            KafkaPublisher publisher,
                            OutboxProperties properties,
                            SbusMetrics metrics,
                            Json json,
                            RateLimiterRegistry rateLimiterRegistry) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
        this.json = json;
        this.coreCommandLimiter = rateLimiterRegistry.rateLimiter("core-command");
    }

    @Transactional
    public int dispatchBatch() {
        List<OutboxEvent> batch = repository.lockPendingBatch(Instant.now(), properties.getBatchSize());
        int published = 0;
        for (OutboxEvent event : batch) {
            // Backpressure to the Core: if the rate limiter denies, leave the row
            // PENDING and try again on the next poll. Other topics flow freely.
            if (Topics.CORE_COMMAND.equals(event.getTopic())
                    && !coreCommandLimiter.acquirePermission()) {
                event.setNextAttemptAt(Instant.now().plusMillis(200));
                repository.update(event);
                continue;
            }
            if (tryPublish(event)) {
                published++;
            }
        }
        return published;
    }

    private boolean tryPublish(OutboxEvent event) {
        try {
            publisher.send(event.getTopic(), event.getKey(), event.getPayload(), parseHeaders(event));
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            event.setLastError(null);
            repository.update(event);
            metrics.recordPublished();
            return true;
        } catch (Exception e) {
            handleFailure(event, e);
            return false;
        }
    }

    private void handleFailure(OutboxEvent event, Exception e) {
        metrics.recordPublishFailure();
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(truncate(e.getMessage()));
        if (attempts >= properties.getMaxAttempts()) {
            routeToDlq(event, e);
            event.setStatus(OutboxStatus.FAILED);
            LOG.error("Outbox event {} exhausted {} attempts -> DLQ", event.getId(), attempts, e);
        } else {
            event.setNextAttemptAt(Instant.now().plus(backoff(attempts)));
            LOG.warn("Outbox publish failed event={} attempt={} retryAt={}",
                    event.getId(), attempts, event.getNextAttemptAt());
        }
        repository.update(event);
    }

    private void routeToDlq(OutboxEvent event, Exception e) {
        try {
            Map<String, String> headers = parseHeaders(event);
            headers.put("x-dlq-origin-topic", event.getTopic());
            headers.put("x-dlq-reason", truncate(e.getMessage()));
            publisher.send(Topics.DLQ, event.getKey(), event.getPayload(), headers);
            metrics.recordDlq();
        } catch (Exception dlqEx) {
            LOG.error("Failed to route outbox event {} to DLQ", event.getId(), dlqEx);
        }
    }

    private Duration backoff(int attempts) {
        long base = properties.getBaseBackoff().toMillis();
        long millis = base * (1L << Math.min(attempts - 1, 16));
        return Duration.ofMillis(Math.min(millis, properties.getMaxBackoff().toMillis()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaders(OutboxEvent event) {
        if (event.getHeaders() == null || event.getHeaders().isBlank()) {
            return new HashMap<>();
        }
        Map<String, String> parsed = json.fromJson(event.getHeaders(), Map.class);
        return new LinkedHashMap<>(parsed);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
