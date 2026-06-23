package com.example.payments.sbus.outbox;

import com.example.payments.sbus.config.OutboxProperties;
import com.example.payments.sbus.repository.OutboxEventRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/** Returns rows stuck IN_PROGRESS (a publisher crashed after claiming) back to PENDING. */
@Singleton
public class OutboxReaper {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxReaper.class);

    private final OutboxEventRepository repository;
    private final OutboxProperties properties;

    public OutboxReaper(OutboxEventRepository repository, OutboxProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = "${sbus.outbox.reaper-interval:30s}", initialDelay = "30s")
    @Transactional
    public void reclaim() {
        Instant threshold = Instant.now().minus(properties.getLease());
        int reclaimed = repository.reclaimStuck(threshold);
        if (reclaimed > 0) {
            LOG.warn("Reclaimed {} stuck IN_PROGRESS outbox row(s) to PENDING", reclaimed);
        }
    }
}
