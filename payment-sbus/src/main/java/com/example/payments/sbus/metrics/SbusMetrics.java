package com.example.payments.sbus.metrics;

import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom SBUS metrics exposed to Prometheus:
 * <ul>
 *   <li>{@code sbus_outbox_pending} – gauge of unpublished outbox rows (alerting target).</li>
 *   <li>{@code sbus_dlq_unconfirmed} – recoverable DLQ work, including active claims.</li>
 *   <li>{@code sbus_dlq_unconfirmed_oldest_age_seconds} – oldest unconfirmed age.</li>
 *   <li>{@code sbus_outbox_published_total} / {@code sbus_outbox_publish_failures_total}.</li>
 *   <li>{@code sbus_dlq_total} – messages routed to the DLQ.</li>
 *   <li>{@code sbus_unrecoverable_message_total} – a record whose own failure could not be
 *       durably persisted either; the payload is preserved in a log line, not in a table.</li>
 *   <li>{@code sbus_end_to_end_latency} – occurredAt(request) -&gt; final event.</li>
 *   <li>{@code sbus_avro_codec_pool_*} – {@link AvroSerde#poolSnapshot()} capacity/available/
 *       borrowed/timeouts (OBS-05).</li>
 * </ul>
 *
 * <p>OBS-04: the three count-based gauges above (pending/unconfirmed/oldest-age) never run a
 * {@code COUNT(*)} on the Prometheus scrape thread — each reads an {@link AtomicLong} refreshed on
 * a fixed 15s schedule ({@link #refreshCachedCounts()}), independent of how often (or rarely) the
 * gauge itself gets scraped.
 */
@Singleton
public class SbusMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(SbusMetrics.class);

    private final MeterRegistry registry;
    private final OutboxEventRepository outboxRepository;
    private final AvroSerde avroSerde;

    private final AtomicLong cachedOutboxPending = new AtomicLong();
    private final AtomicLong cachedDlqUnconfirmed = new AtomicLong();
    private final AtomicLong cachedDlqUnconfirmedOldestAgeSeconds = new AtomicLong();

    private Counter outboxPublished;
    private Counter outboxPublishFailures;
    private Counter dlq;
    private Counter unrecoverable;
    private Timer endToEndLatency;

    public SbusMetrics(MeterRegistry registry, OutboxEventRepository outboxRepository, AvroSerde avroSerde) {
        this.registry = registry;
        this.outboxRepository = outboxRepository;
        this.avroSerde = avroSerde;
    }

    @PostConstruct
    void init() {
        // One synchronous population at boot (not on a scrape thread) so the gauges below never
        // read a stale zero before the first scheduled refresh fires. Best-effort only: a
        // @PostConstruct failure aborts the whole application context, and Postgres being down
        // at boot must never crash-loop the SBUS instead of starting degraded and retrying —
        // the scheduled refresh below will populate the real counts once the database recovers.
        try {
            refreshCachedCounts();
        } catch (Exception e) {
            LOG.warn("Could not populate outbox metric gauges at startup, will retry on the next "
                    + "scheduled refresh: {}", e.getMessage());
        }
        registry.gauge("sbus_outbox_pending", cachedOutboxPending, AtomicLong::get);
        registry.gauge("sbus_dlq_unconfirmed", cachedDlqUnconfirmed, AtomicLong::get);
        registry.gauge("sbus_dlq_unconfirmed_oldest_age_seconds",
                cachedDlqUnconfirmedOldestAgeSeconds, AtomicLong::get);
        registry.gauge("sbus_avro_codec_pool_capacity", avroSerde, s -> s.poolSnapshot().capacity());
        registry.gauge("sbus_avro_codec_pool_available", avroSerde, s -> s.poolSnapshot().available());
        registry.gauge("sbus_avro_codec_pool_borrowed", avroSerde, s -> s.poolSnapshot().borrowed());
        registry.gauge("sbus_avro_codec_pool_timeouts_total", avroSerde, s -> s.poolSnapshot().timeouts());
        this.outboxPublished = registry.counter("sbus_outbox_published_total");
        this.outboxPublishFailures = registry.counter("sbus_outbox_publish_failures_total");
        this.dlq = registry.counter("sbus_dlq_total");
        this.unrecoverable = registry.counter("sbus_unrecoverable_message_total");
        this.endToEndLatency = Timer.builder("sbus_end_to_end_latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /**
     * OBS-04: runs off the scrape thread, on its own fixed schedule (TTL 15s, well under the 30s
     * ceiling) — package-private so a unit test can trigger a refresh deterministically instead of
     * waiting on the real schedule.
     */
    @Scheduled(fixedDelay = "${sbus.metrics.count-cache-ttl:15s}")
    void refreshCachedCounts() {
        cachedOutboxPending.set(outboxRepository.countByStatus(OutboxStatus.PENDING));
        cachedDlqUnconfirmed.set(outboxRepository.countUnconfirmedDeadLetters());
        cachedDlqUnconfirmedOldestAgeSeconds.set(outboxRepository.oldestUnconfirmedDeadLetterAgeSeconds());
    }

    public void recordPublished(int count) {
        outboxPublished.increment(count);
    }

    public void recordPublishFailure() {
        outboxPublishFailures.increment();
    }

    public void recordDlq() {
        dlq.increment();
    }

    /**
     * A record's own failure could not be durably persisted either (both the main handler and
     * the retry/DLQ scheduler failed — typically Postgres itself is down). The record is not
     * silently lost: its raw payload is logged (see RetryPublisher) so it can be replayed by
     * hand, but this metric is the only automated signal that it happened. Alert on it above 0.
     */
    public void recordUnrecoverable() {
        unrecoverable.increment();
    }

    public void recordEndToEnd(Duration duration) {
        endToEndLatency.record(duration);
    }
}
