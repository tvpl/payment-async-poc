package com.example.payments.sbus.health;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.data.connection.ConnectionOperations;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.management.health.indicator.annotation.Liveness;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

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
 * own {@code MicrometerMetricsTrackerFactory} onto this exact {@link javax.sql.DataSource} bean
 * whenever a {@link io.micrometer.core.instrument.MeterRegistry} is present (which it is here),
 * so this class only adds the health check, not a second, differently-named set of gauges — see
 * {@code ops/dashboards/postgres-pool.json} and {@code load/capacity/collect_metrics.sh}, both
 * already written against {@code hikaricp_connections_active}/{@code _pending}.
 *
 * <p>Acquires the connection through {@link ConnectionOperations#executeRead}, not a raw
 * {@code DataSource.getConnection()}: with {@code micronaut-data-jdbc} on the classpath, the
 * injected {@code Connection} is context-managed and throws {@code NoConnectionException} on
 * {@code close()} outside a {@code @Connectable}/{@code @Transactional} scope (or a programmatic
 * one, as here) — the acquisition itself would still succeed, but every check would leak the
 * physical connection back into the pool as permanently checked-out, exhausting it after
 * {@code maximum-pool-size} liveness probes.
 *
 * <p>Deliberately {@code @Liveness}, not {@code @Readiness}: pool pressure alone should not take
 * the instance out of rotation the way a real dependency outage does — it is surfaced instead via
 * {@code /health} plus the gauges/alert. A bean with neither qualifier defaults to being included
 * in {@code /health/readiness} anyway (Micronaut's own default), which would silently defeat this
 * intent — {@code @Liveness} is what actually keeps it out of the readiness aggregate.
 */
@Singleton
@Liveness
public class HikariPoolHealthIndicator implements HealthIndicator {

    private static final String NAME = "postgresql-pool";

    private final ConnectionOperations<Connection> connectionOperations;
    private final Duration acquireTimeout;
    private final ExecutorService probeExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "postgresql-pool-health-probe");
                thread.setDaemon(true);
                return thread;
            });

    public HikariPoolHealthIndicator(ConnectionOperations<Connection> connectionOperations,
                                     @Value("${sbus.health.pool-acquire-timeout:2s}") Duration acquireTimeout) {
        this.connectionOperations = connectionOperations;
        this.acquireTimeout = acquireTimeout;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        HealthResult.Builder builder = HealthResult.builder(NAME);
        Future<Boolean> attempt = probeExecutor.submit((Callable<Boolean>) () ->
                connectionOperations.executeRead(status -> status.getConnection() != null));
        try {
            attempt.get(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
            builder.status(HealthStatus.UP);
        } catch (TimeoutException timeout) {
            attempt.cancel(true);
            builder.status(HealthStatus.DOWN).exception(timeout);
        } catch (Exception failure) {
            builder.status(HealthStatus.DOWN).exception(failure);
        }
        return Publishers.just(builder.build());
    }
}
