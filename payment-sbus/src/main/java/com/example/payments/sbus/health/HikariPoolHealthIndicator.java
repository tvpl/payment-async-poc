package com.example.payments.sbus.health;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RES-05: {@code postgresql-pool} health indicator — a short-timeout attempt to actually acquire
 * a connection from the pool, distinct from {@link PostgresHealthIndicator} (which bypasses the
 * pool entirely with a direct, short-lived connection to detect PostgreSQL itself being down).
 * This one catches the pool being unable to hand out a connection at all — exhausted under load,
 * or every physical connection stuck — even while PostgreSQL is healthy.
 *
 * <p>The active/idle/pending/timeout gauges the design calls for are already exposed under the
 * standard {@code hikaricp_connections_*} names: {@code micronaut-jdbc-hikari} wires HikariCP's
 * own {@code MicrometerMetricsTrackerFactory} onto this exact {@link DataSource} bean whenever a
 * {@link io.micrometer.core.instrument.MeterRegistry} is present (which it is here), so this
 * class only adds the health check, not a second, differently-named set of gauges — see
 * {@code ops/dashboards/postgres-pool.json} and {@code load/capacity/collect_metrics.sh}, both
 * already written against {@code hikaricp_connections_active}/{@code _pending}.
 *
 * <p>Deliberately a plain indicator, not {@code @Readiness}: pool pressure alone should not take
 * the instance out of rotation the way a real dependency outage does — it is surfaced instead via
 * {@code /health} plus the gauges/alert.
 */
@Singleton
public class HikariPoolHealthIndicator implements HealthIndicator {

    private static final String NAME = "postgresql-pool";

    private final DataSource dataSource;
    private final Duration acquireTimeout;
    private final ExecutorService probeExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "postgresql-pool-health-probe");
                thread.setDaemon(true);
                return thread;
            });

    public HikariPoolHealthIndicator(DataSource dataSource,
                                     @Value("${sbus.health.pool-acquire-timeout:2s}") Duration acquireTimeout) {
        this.dataSource = dataSource;
        this.acquireTimeout = acquireTimeout;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        HealthResult.Builder builder = HealthResult.builder(NAME);
        Future<Connection> attempt = probeExecutor.submit((Callable<Connection>) dataSource::getConnection);
        try {
            try (Connection connection = attempt.get(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                builder.status(HealthStatus.UP);
            }
        } catch (TimeoutException timeout) {
            attempt.cancel(true);
            builder.status(HealthStatus.DOWN).exception(timeout);
        } catch (Exception failure) {
            builder.status(HealthStatus.DOWN).exception(failure);
        }
        return Publishers.just(builder.build());
    }
}
