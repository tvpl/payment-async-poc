package com.example.payments.sbus.outbox;

import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.OutboxProperties;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.support.Json;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reclaims rows stuck IN_PROGRESS past their claim lease. Each reclaim is routed through
 * {@link OutboxClaimService#markFailure} exactly like a real publish failure (AUD-08):
 * {@code attempts} is incremented and a backoff-based {@code next_attempt_at} is set — not a
 * blind reset. A row that's always mid-flight when reaped (a crash loop on the owning instance,
 * a publish that never completes) therefore reaches {@code max-attempts} and moves to the DLQ,
 * instead of looping hot forever: reclaimed, re-claimed by the dispatcher, stuck again,
 * reclaimed again, at full speed, with no backoff and no way out.
 */
@Singleton
public class OutboxReaper {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxReaper.class);

    private final OutboxEventRepository repository;
    private final OutboxClaimService claimService;
    private final OutboxProperties properties;
    private final Json json;

    public OutboxReaper(OutboxEventRepository repository, OutboxClaimService claimService,
                        OutboxProperties properties, Json json) {
        this.repository = repository;
        this.claimService = claimService;
        this.properties = properties;
        this.json = json;
    }

    @Scheduled(fixedDelay = "${sbus.outbox.reaper-interval:30s}", initialDelay = "30s")
    @Transactional
    public int reclaim() {
        Instant threshold = Instant.now().minus(properties.getLease());
        List<OutboxEvent> stuck = repository.findStuckBatch(threshold, properties.getBatchSize());
        int reclaimed = 0;
        for (OutboxEvent event : stuck) {
            String error = "Reclaimed: stuck IN_PROGRESS since " + event.getClaimedAt()
                    + " (claim lease exceeded)";
            var disposition = claimService.markFailure(event, error, dlqHeaders(event));
            if (disposition != OutboxClaimService.FailureDisposition.STALE_CLAIM) {
                reclaimed++;
            }
        }
        if (reclaimed > 0) {
            LOG.warn("Reclaimed {} stuck IN_PROGRESS outbox row(s) to a recoverable queue", reclaimed);
        }
        return reclaimed;
    }

    /**
     * Pre-built in case this reclaim is the one that exhausts {@code max-attempts} — mirrors
     * {@code OutboxDispatcher.publish}'s catch block so a reaper-driven DLQ entry carries the
     * same provenance headers as a publish-failure-driven one. Unused when the row is merely
     * retried (see {@code OutboxClaimService#markFailure}).
     *
     * <p>A row already ON the DLQ topic (reclaimed while stuck mid-delivery to the DLQ itself)
     * keeps its existing headers untouched — this reclaim isn't a NEW reason the message is
     * headed to the DLQ, it's a retry of the same delivery, and the original provenance
     * ({@code x-dlq-stage}, {@code x-dlq-reason}) must survive it.
     */
    @SuppressWarnings("unchecked")
    private String dlqHeaders(OutboxEvent event) {
        Map<String, String> headers = event.getHeaders() == null || event.getHeaders().isBlank()
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(json.fromJson(event.getHeaders(), Map.class));
        if (Topics.DLQ.equals(event.getTopic())) {
            return json.toJson(headers);
        }
        headers.put("x-dlq-origin-topic", headers.getOrDefault("x-dlq-origin-topic", event.getTopic()));
        headers.put("x-dlq-stage", "outbox-reaper");
        headers.put("x-dlq-reason", "max-attempts exceeded while stuck IN_PROGRESS past the claim lease");
        return json.toJson(headers);
    }
}
