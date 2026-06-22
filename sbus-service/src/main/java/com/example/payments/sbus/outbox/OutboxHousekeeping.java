package com.example.payments.sbus.outbox;

import com.example.payments.sbus.config.OutboxProperties;
import com.example.payments.sbus.repository.OutboxEventRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/** Purges PUBLISHED outbox rows older than the retention window, so the table stays small. */
@Singleton
public class OutboxHousekeeping {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxHousekeeping.class);

    private final OutboxEventRepository repository;
    private final OutboxProperties properties;

    public OutboxHousekeeping(OutboxEventRepository repository, OutboxProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = "${sbus.outbox.housekeeping-interval:1h}", initialDelay = "1h")
    @Transactional
    public void purge() {
        Instant threshold = Instant.now().minus(properties.getRetention());
        int deleted = repository.deletePublishedBefore(threshold);
        if (deleted > 0) {
            LOG.info("Outbox housekeeping purged {} published row(s)", deleted);
        }
    }
}
