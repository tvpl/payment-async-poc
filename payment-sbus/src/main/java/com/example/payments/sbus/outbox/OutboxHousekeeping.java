package com.example.payments.sbus.outbox;

import com.example.payments.sbus.config.OutboxProperties;
import com.example.payments.sbus.repository.OutboxEventRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Purges PUBLISHED outbox rows older than the retention window, so the table stays small.
 * Deletes in bounded batches (AUD-24), like every other retention purge here (see
 * {@code RetentionHousekeeping}) — an unbounded {@code DELETE} on a table that can grow into the
 * millions holds its lock for as long as the whole scan takes.
 */
@Singleton
public class OutboxHousekeeping {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxHousekeeping.class);

    private final OutboxEventRepository repository;
    private final OutboxProperties properties;

    public OutboxHousekeeping(OutboxEventRepository repository, OutboxProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    // AUD-23: initialDelay now reads sbus.outbox.housekeeping-initial-delay instead of a
    // hardcoded literal, matching every other tunable on this job.
    @Scheduled(fixedDelay = "${sbus.outbox.housekeeping-interval:1h}",
            initialDelay = "${sbus.outbox.housekeeping-initial-delay:1h}")
    @Transactional
    public void purge() {
        Instant threshold = Instant.now().minus(properties.getRetention());
        int deleted = repository.deletePublishedBefore(threshold, properties.getBatchSize());
        if (deleted > 0) {
            LOG.info("Outbox housekeeping purged {} published row(s)", deleted);
        }
    }
}
