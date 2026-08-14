package com.example.payments.sbus.outbox;

import com.example.payments.sbus.config.OutboxProperties;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.repository.OutboxEventRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
            e.setClaimToken(UUID.randomUUID());
            repository.update(e);
        }
        return batch;
    }

    /**
     * Completes a single current-owner claim; a stale token affects zero rows. Called once per
     * event, immediately after that event's own Kafka send succeeds — not accumulated and marked
     * only after the whole batch finishes. A batch-wide mark would leave every already-sent event
     * unrecorded (and therefore re-claimable once its lease expires, republishing something the
     * Core already processed) if the loop is interrupted partway through, whether by the claim
     * lease expiring mid-batch or by an unrelated later item's failure aborting the loop.
     */
    @Transactional
    public boolean markPublished(OutboxEvent event) {
        return repository.markPublished(event.getId(), event.getClaimToken(), Instant.now()) > 0;
    }

    /** Tx2: normal failures retry; exhaustion and every DLQ failure remain DLQ_PENDING. */
    @Transactional
    public FailureDisposition markFailure(OutboxEvent event, String error, String dlqHeaders) {
        int attempts = event.getAttempts() + 1;
        boolean dlq = Topics.DLQ.equals(event.getTopic()) || attempts >= properties.getMaxAttempts();
        OutboxStatus nextStatus = dlq ? OutboxStatus.DLQ_PENDING : OutboxStatus.PENDING;
        String nextTopic = dlq ? Topics.DLQ : event.getTopic();
        String nextHeaders = dlq ? dlqHeaders : event.getHeaders();
        String lastError = truncate(error);
        Instant nextAttemptAt = Instant.now().plus(
                BackoffCalculator.backoff(attempts, properties.getBaseBackoff(), properties.getMaxBackoff()));
        int updated = repository.markFailedAttempt(event.getId(), event.getClaimToken(),
                nextStatus.name(), nextTopic, nextHeaders, attempts, nextAttemptAt, lastError);
        if (updated == 0) {
            return FailureDisposition.STALE_CLAIM;
        }
        return dlq ? FailureDisposition.DLQ_PENDING : FailureDisposition.RETRY_PENDING;
    }

    /**
     * Renews the lease of every row in {@code remaining}, called by {@link OutboxDispatcher}
     * after each row's own publish turn so a slow batch's still-unprocessed rows never outlive
     * their lease mid-way through (AUD-07). Stops at the first row whose claim no longer matches
     * — something else (the reaper, on a genuinely expired lease) already reclaimed it — since
     * continuing to trust this batch's ownership of the rest would risk a double-publish.
     *
     * @return false if any row's claim could not be renewed (fence lost)
     */
    @Transactional
    public boolean renewRemaining(List<OutboxEvent> remaining) {
        Instant now = Instant.now();
        for (OutboxEvent event : remaining) {
            if (repository.renewClaim(event.getId(), event.getClaimToken(), now) == 0) {
                return false;
            }
        }
        return true;
    }

    /** Releases a still-PENDING-after-throttle row (rate limiter denied the Core command). */
    @Transactional
    public void release(OutboxEvent event, Instant nextAttemptAt) {
        String status = Topics.DLQ.equals(event.getTopic())
                ? OutboxStatus.DLQ_PENDING.name()
                : OutboxStatus.PENDING.name();
        repository.releaseClaim(event.getId(), event.getClaimToken(), status, nextAttemptAt);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    public enum FailureDisposition {
        RETRY_PENDING,
        DLQ_PENDING,
        STALE_CLAIM
    }
}
