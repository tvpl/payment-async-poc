package com.example.payments.sbus.outbox;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the outbox: polls on a fixed delay and delegates the transactional
 * publish to {@link OutboxDispatcher} (kept in a separate bean so the
 * {@code @Transactional} proxy applies — self-invocation would bypass it).
 */
@Singleton
public class OutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxDispatcher dispatcher;

    public OutboxPublisher(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelay = "${sbus.outbox.poll-interval:500ms}",
            initialDelay = "${sbus.outbox.initial-delay:2s}")
    void poll() {
        try {
            int published = dispatcher.dispatchBatch();
            if (published > 0) {
                LOG.debug("Outbox published {} event(s)", published);
            }
        } catch (Exception e) {
            LOG.error("Outbox poll failed", e);
        }
    }
}
