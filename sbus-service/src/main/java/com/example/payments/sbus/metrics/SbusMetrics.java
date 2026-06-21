package com.example.payments.sbus.metrics;

import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;

import java.time.Duration;

/**
 * Custom SBUS metrics exposed to Prometheus:
 * <ul>
 *   <li>{@code sbus_outbox_pending} – gauge of unpublished outbox rows (alerting target).</li>
 *   <li>{@code sbus_outbox_published_total} / {@code sbus_outbox_publish_failures_total}.</li>
 *   <li>{@code sbus_dlq_total} – messages routed to the DLQ.</li>
 *   <li>{@code sbus_end_to_end_latency} – occurredAt(request) -&gt; final event.</li>
 * </ul>
 */
@Singleton
public class SbusMetrics {

    private final MeterRegistry registry;
    private final OutboxEventRepository outboxRepository;

    private Counter outboxPublished;
    private Counter outboxPublishFailures;
    private Counter dlq;
    private Timer endToEndLatency;

    public SbusMetrics(MeterRegistry registry, OutboxEventRepository outboxRepository) {
        this.registry = registry;
        this.outboxRepository = outboxRepository;
    }

    @PostConstruct
    void init() {
        registry.gauge("sbus_outbox_pending", this,
                m -> m.outboxRepository.countByStatus(OutboxStatus.PENDING));
        this.outboxPublished = registry.counter("sbus_outbox_published_total");
        this.outboxPublishFailures = registry.counter("sbus_outbox_publish_failures_total");
        this.dlq = registry.counter("sbus_dlq_total");
        this.endToEndLatency = Timer.builder("sbus_end_to_end_latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void recordPublished() {
        outboxPublished.increment();
    }

    public void recordPublishFailure() {
        outboxPublishFailures.increment();
    }

    public void recordDlq() {
        dlq.increment();
    }

    public void recordEndToEnd(Duration duration) {
        endToEndLatency.record(duration);
    }
}
