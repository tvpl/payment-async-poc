package com.example.payments.sbus.outbox;

import com.example.payments.sbus.config.HousekeepingProperties;
import com.example.payments.sbus.repository.IdempotencyRecordRepository;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
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
 * Bounds unbounded growth: purges old {@code idempotency_record} rows and old <em>terminal</em>
 * {@code payment_sbus_message} rows (the durable status fallback only needs recent ones).
 * Deletes in bounded batches to keep locks short.
 *
 * <p>RES-02: each of the two purges loops batch after batch until either nothing eligible is
 * left or the shared time cap for this run is hit, instead of purging at most one batch per
 * scheduled interval regardless of backlog size.
 */
@Singleton
public class RetentionHousekeeping {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionHousekeeping.class);

    private final IdempotencyRecordRepository idempotencyRepository;
    private final PaymentSbusMessageRepository messageRepository;
    private final HousekeepingProperties properties;
    private final MeterRegistry registry;
    private final AtomicLong idempotencyRemaining = new AtomicLong();
    private final AtomicLong messageRemaining = new AtomicLong();

    private Counter idempotencyPurged;
    private Counter messagePurged;

    public RetentionHousekeeping(IdempotencyRecordRepository idempotencyRepository,
                                 PaymentSbusMessageRepository messageRepository,
                                 HousekeepingProperties properties,
                                 MeterRegistry registry) {
        this.idempotencyRepository = idempotencyRepository;
        this.messageRepository = messageRepository;
        this.properties = properties;
        this.registry = registry;
    }

    @PostConstruct
    void init() {
        idempotencyPurged = registry.counter("sbus_housekeeping_idempotency_purged_total");
        messagePurged = registry.counter("sbus_housekeeping_message_purged_total");
        registry.gauge("sbus_housekeeping_idempotency_remaining", idempotencyRemaining);
        registry.gauge("sbus_housekeeping_message_remaining", messageRemaining);
    }

    @Scheduled(fixedDelay = "${sbus.housekeeping.interval:1h}", initialDelay = "1h")
    public void purge() {
        Instant deadline = Instant.now().plus(properties.getTimeCap());
        int idem = drainIdempotency(deadline);
        int msg = drainMessages(deadline);
        if (idem > 0 || msg > 0) {
            LOG.info("Retention purge: idempotency_record={} payment_sbus_message={}", idem, msg);
        }
    }

    private int drainIdempotency(Instant deadline) {
        Instant threshold = Instant.now().minus(properties.getIdempotencyRetention());
        int total = 0;
        int deletedInBatch;
        do {
            deletedInBatch = idempotencyRepository.deleteCreatedBefore(threshold, properties.getBatchSize());
            total += deletedInBatch;
        } while (deletedInBatch == properties.getBatchSize() && Instant.now().isBefore(deadline));

        long stillEligible = idempotencyRepository.countCreatedBefore(threshold);
        idempotencyPurged.increment(total);
        idempotencyRemaining.set(stillEligible);
        return total;
    }

    private int drainMessages(Instant deadline) {
        Instant threshold = Instant.now().minus(properties.getMessageRetention());
        int total = 0;
        int deletedInBatch;
        do {
            deletedInBatch = messageRepository.deleteTerminalUpdatedBefore(threshold, properties.getBatchSize());
            total += deletedInBatch;
        } while (deletedInBatch == properties.getBatchSize() && Instant.now().isBefore(deadline));

        long stillEligible = messageRepository.countTerminalUpdatedBefore(threshold);
        messagePurged.increment(total);
        messageRemaining.set(stillEligible);
        return total;
    }
}
