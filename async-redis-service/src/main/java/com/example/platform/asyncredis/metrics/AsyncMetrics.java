package com.example.platform.asyncredis.metrics;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;

import java.time.Duration;

/**
 * Prometheus metrics for the async flow, so backlog and processing latency are observable in Grafana:
 * <ul>
 *   <li>{@code async_stream_length} — gauge of {@code XLEN} (queued jobs).</li>
 *   <li>{@code async_pending} — gauge of the consumer group's Pending Entries List (in-flight/unacked).</li>
 *   <li>{@code async_process_latency} — timer of worker processing time.</li>
 * </ul>
 * Only wired when a {@link MeterRegistry} exists.
 */
@Singleton
@Requires(beans = MeterRegistry.class)
public class AsyncMetrics {

    private final MeterRegistry registry;
    private final RedisConnections redis;
    private final AsyncRedisProperties props;
    private Timer processLatency;

    public AsyncMetrics(MeterRegistry registry, RedisConnections redis, AsyncRedisProperties props) {
        this.registry = registry;
        this.redis = redis;
        this.props = props;
    }

    @PostConstruct
    void init() {
        Gauge.builder("async_stream_length", this, AsyncMetrics::streamLength).register(registry);
        Gauge.builder("async_pending", this, AsyncMetrics::pending).register(registry);
        this.processLatency = Timer.builder("async_process_latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void recordProcessing(Duration duration) {
        if (processLatency != null) {
            processLatency.record(duration);
        }
    }

    private double streamLength() {
        try {
            Long len = redis.shared().xlen(props.getStream());
            return len == null ? 0 : len;
        } catch (Exception e) {
            return 0;
        }
    }

    private double pending() {
        try {
            var summary = redis.shared().xpending(props.getStream(), props.getGroup());
            return summary == null ? 0 : summary.getCount();
        } catch (Exception e) {
            return 0;
        }
    }
}
