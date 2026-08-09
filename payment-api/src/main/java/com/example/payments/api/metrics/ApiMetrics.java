package com.example.payments.api.metrics;

import com.example.payments.api.coordination.ResponseCoordinator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;

import java.time.Duration;

/**
 * API metrics exposed to Prometheus:
 * <ul>
 *   <li>{@code api_requests_total} – accepted simulation requests.</li>
 *   <li>{@code api_timeouts_total} – requests that returned 202 (no result in time).</li>
 *   <li>{@code api_completed_total} / {@code api_failed_total}.</li>
 *   <li>{@code api_wait_latency} – time spent blocking for the async result.</li>
 *   <li>{@code api_pending} – gauge of requests currently waiting (PENDING).</li>
 * </ul>
 */
@Singleton
public class ApiMetrics {

    private final MeterRegistry registry;
    private final ResponseCoordinator coordinator;

    private Counter timeouts;
    private Counter completed;
    private Counter failed;
    private Timer waitLatency;

    public ApiMetrics(MeterRegistry registry, ResponseCoordinator coordinator) {
        this.registry = registry;
        this.coordinator = coordinator;
    }

    @PostConstruct
    void init() {
        this.timeouts = registry.counter("api_timeouts_total");
        this.completed = registry.counter("api_completed_total");
        this.failed = registry.counter("api_failed_total");
        this.waitLatency = Timer.builder("api_wait_latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        registry.gauge("api_pending", coordinator, ResponseCoordinator::pendingCount);
    }

    /** Tagged by payment method (low cardinality) for per-method request rates. */
    public void recordRequest(String paymentMethod) {
        registry.counter("api_requests_total", "payment_method",
                paymentMethod == null ? "unknown" : paymentMethod).increment();
    }

    public void recordTimeout() {
        timeouts.increment();
    }

    public void recordCompleted() {
        completed.increment();
    }

    public void recordFailed() {
        failed.increment();
    }

    public void recordWait(Duration duration) {
        waitLatency.record(duration);
    }
}
