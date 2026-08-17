package com.example.payments.sbus.outbox;

import com.example.payments.sbus.config.OutboxProperties;
import com.example.payments.sbus.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Purges PUBLISHED outbox rows older than the retention window, so the table stays small.
 * Deletes in bounded batches (AUD-24), like every other retention purge here (see
 * {@code RetentionHousekeeping}) — an unbounded {@code DELETE} on a table that can grow into the
 * millions holds its lock for as long as the whole scan takes.
 *
 * <p>RES-02: one run now loops batch after batch — each its own short auto-committed statement,
 * not one long transaction — until either nothing eligible is left or a configurable time cap is
 * hit, instead of purging at most one batch per scheduled interval regardless of how large the
 * backlog is. A backlog that grew during an outage can then drain proportionally to how big it
 * is, not at a fixed trickle.
 */
@Singleton
public class OutboxHousekeeping {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxHousekeeping.class);

    private final OutboxEventRepository repository;
    private final OutboxProperties properties;
    private final MeterRegistry registry;
    private final AtomicLong remaining = new AtomicLong();

    private Counter purged;

    public OutboxHousekeeping(OutboxEventRepository repository, OutboxProperties properties,
                              MeterRegistry registry) {
        this.repository = repository;
        this.properties = properties;
        this.registry = registry;
    }

    @PostConstruct
    void init() {
        purged = registry.counter("sbus_outbox_housekeeping_purged_total");
        registry.gauge("sbus_outbox_housekeeping_remaining", remaining);
    }

    // AUD-23: initialDelay now reads sbus.outbox.housekeeping-initial-delay instead of a
    // hardcoded literal, matching every other tunable on this job.
    @Scheduled(fixedDelay = "${sbus.outbox.housekeeping-interval:1h}",
            initialDelay = "${sbus.outbox.housekeeping-initial-delay:1h}")
    public void purge() {
        Instant threshold = Instant.now().minus(properties.getRetention());
        Instant deadline = Instant.now().plus(properties.getHousekeepingTimeCap());
        int totalDeleted = 0;
        int deletedInBatch;
        do {
            deletedInBatch = repository.deletePublishedBefore(threshold, properties.getBatchSize());
            totalDeleted += deletedInBatch;
        } while (deletedInBatch == properties.getBatchSize() && Instant.now().isBefore(deadline));

        long stillEligible = repository.countPublishedBefore(threshold);
        purged.increment(totalDeleted);
        remaining.set(stillEligible);
        if (totalDeleted > 0) {
            LOG.info("Outbox housekeeping purged {} published row(s), {} still eligible",
                    totalDeleted, stillEligible);
        }
    }
}
