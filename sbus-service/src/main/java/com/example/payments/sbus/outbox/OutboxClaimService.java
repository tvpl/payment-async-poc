package com.example.payments.sbus.outbox;

import com.example.payments.sbus.config.OutboxProperties;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.repository.OutboxEventRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Short transactional operations around the outbox. Kept separate from
 * {@link OutboxDispatcher} so the {@code @Transactional} proxy applies and so the
 * slow Kafka publish happens <em>outside</em> any DB transaction (no long-held locks).
 */
@Singleton
public class OutboxClaimService {

    private final OutboxEventRepository repository;
    private final OutboxProperties properties;

    public OutboxClaimService(OutboxEventRepository repository, OutboxProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** Tx1: lock a due batch and flip it to IN_PROGRESS, returning the claimed rows. */
    @Transactional
    public List<OutboxEvent> claimBatch() {
        Instant now = Instant.now();
        List<OutboxEvent> batch = repository.lockPendingBatch(now, properties.getBatchSize());
        for (OutboxEvent e : batch) {
            e.setStatus(OutboxStatus.IN_PROGRESS);
            e.setClaimedAt(now);
            repository.update(e);
        }
        return batch;
    }

    @Transactional
    public void markPublished(OutboxEvent event) {
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setPublishedAt(Instant.now());
        event.setClaimedAt(null);
        event.setLastError(null);
        repository.update(event);
    }

    /** Tx2 (failure): back to PENDING with backoff, or FAILED once attempts are exhausted. */
    @Transactional
    public boolean markFailure(OutboxEvent event, String error) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(truncate(error));
        event.setClaimedAt(null);
        boolean dead = attempts >= properties.getMaxAttempts();
        if (dead) {
            event.setStatus(OutboxStatus.FAILED);
        } else {
            event.setStatus(OutboxStatus.PENDING);
            event.setNextAttemptAt(Instant.now().plus(
                    BackoffCalculator.backoff(attempts, properties.getBaseBackoff(), properties.getMaxBackoff())));
        }
        repository.update(event);
        return dead;
    }

    /** Releases a still-PENDING-after-throttle row (rate limiter denied the Core command). */
    @Transactional
    public void release(OutboxEvent event, Instant nextAttemptAt) {
        event.setStatus(OutboxStatus.PENDING);
        event.setClaimedAt(null);
        event.setNextAttemptAt(nextAttemptAt);
        repository.update(event);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
