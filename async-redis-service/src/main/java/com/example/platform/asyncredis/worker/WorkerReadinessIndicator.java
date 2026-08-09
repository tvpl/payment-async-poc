package com.example.platform.asyncredis.worker;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.management.health.indicator.annotation.Readiness;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports the consuming side on {@code /health/readiness} (RED-05): DOWN until at least one worker
 * has read from the stream on a live connection, and DOWN again if every worker loses Redis.
 */
@Singleton
@Readiness
public class WorkerReadinessIndicator implements HealthIndicator {

    private static final String NAME = "async-redis-workers";

    private final WorkerReadiness readiness;
    private final AsyncRedisProperties props;

    public WorkerReadinessIndicator(WorkerReadiness readiness, AsyncRedisProperties props) {
        this.readiness = readiness;
        this.props = props;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        boolean up = readiness.hasConsumingCapacity();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("consumingWorkers", readiness.consumingWorkers());
        details.put("configuredWorkers", Math.max(1, props.getWorkerConcurrency()));
        return Publishers.just(HealthResult.builder(NAME, up ? HealthStatus.UP : HealthStatus.DOWN)
                .details(details)
                .build());
    }
}
