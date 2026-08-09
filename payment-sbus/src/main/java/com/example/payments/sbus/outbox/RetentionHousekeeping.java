package com.example.payments.sbus.outbox;

import com.example.payments.sbus.config.HousekeepingProperties;
import com.example.payments.sbus.repository.IdempotencyRecordRepository;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Bounds unbounded growth: purges old {@code idempotency_record} rows and old <em>terminal</em>
 * {@code payment_sbus_message} rows (the durable status fallback only needs recent ones).
 * Deletes in bounded batches to keep locks short.
 */
@Singleton
public class RetentionHousekeeping {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionHousekeeping.class);

    private final IdempotencyRecordRepository idempotencyRepository;
    private final PaymentSbusMessageRepository messageRepository;
    private final HousekeepingProperties properties;

    public RetentionHousekeeping(IdempotencyRecordRepository idempotencyRepository,
                                 PaymentSbusMessageRepository messageRepository,
                                 HousekeepingProperties properties) {
        this.idempotencyRepository = idempotencyRepository;
        this.messageRepository = messageRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = "${sbus.housekeeping.interval:1h}", initialDelay = "1h")
    @Transactional
    public void purge() {
        int idem = idempotencyRepository.deleteCreatedBefore(
                Instant.now().minus(properties.getIdempotencyRetention()), properties.getBatchSize());
        int msg = messageRepository.deleteTerminalUpdatedBefore(
                Instant.now().minus(properties.getMessageRetention()), properties.getBatchSize());
        if (idem > 0 || msg > 0) {
            LOG.info("Retention purge: idempotency_record={} payment_sbus_message={}", idem, msg);
        }
    }
}
