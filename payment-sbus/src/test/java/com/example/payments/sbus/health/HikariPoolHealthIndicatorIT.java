package com.example.payments.sbus.health;

import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.health.HealthStatus;
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.management.health.indicator.HealthResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RES-05: {@code postgresql-pool} must go DOWN, within its own short acquisition budget, when the
 * pool genuinely cannot hand out a connection — even though PostgreSQL itself is perfectly
 * healthy the whole time (that distinction is exactly why this check exists alongside the
 * PostgreSQL-bypassing {@link PostgresHealthIndicator}).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HikariPoolHealthIndicatorIT {

    /**
     * The pool must be big enough for Flyway to migrate at boot and no bigger, so that the
     * exhaustion test below can hold every connection there is.
     *
     * <p>Two is that minimum, and it is a hard floor, not a cushion: {@code micronaut-flyway}
     * migrates from a {@code BeanCreatedEventListener} on the {@link DataSource} itself
     * ({@code DataSourceMigrationRunner.onCreated}), and Flyway's {@code DbMigrate} opens a
     * second, separate physical connection via {@code Database.getMigrationConnection()} while
     * its main schema-history connection is still checked out. With {@code maximum-pool-size=1}
     * Flyway therefore waits on itself for the full {@code connection-timeout} and the whole
     * context fails to start with {@code HikariPool-1 - Connection is not available ...
     * (total=1, active=1)} — a self-deadlock in the test's own configuration, not a leak in
     * any application bean. Production runs a pool of 10 (see {@code application.yml}), so this
     * floor constrains the test only.
     */
    private static final int MAX_POOL_SIZE = 2;

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    private ApplicationContext context;
    private HikariPoolHealthIndicator indicator;
    private DataSource dataSource;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        indicator = context.getBean(HikariPoolHealthIndicator.class);
        dataSource = rawPool(context);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void reportsUpWhenAConnectionIsAvailable() throws Exception {
        HealthResult result = blockingResult(indicator.getResult());

        assertEquals(HealthStatus.UP, result.getStatus());
    }

    /**
     * Done-when: "pool esgotado -> indicator DOWN dentro do budget". Every one of the pool's
     * {@value #MAX_POOL_SIZE} connections is held open by this test, so the indicator's own
     * bounded acquisition attempt has nothing left to acquire and must report DOWN, well inside
     * the 2s acquire-timeout budget plus a generous margin for the round trip.
     */
    @Test
    void reportsDownWithinTheAcquireBudgetWhenThePoolIsExhausted() throws Exception {
        List<Connection> held = new ArrayList<>();
        try {
            while (held.size() < MAX_POOL_SIZE) {
                held.add(dataSource.getConnection());
            }
            long start = System.nanoTime();

            HealthResult result = blockingResult(indicator.getResult());

            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertEquals(HealthStatus.DOWN, result.getStatus());
            assertTrue(elapsedMillis < 10_000,
                    "the exhausted-pool check must fail within its own budget, took " + elapsedMillis + "ms");
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    private static HealthResult blockingResult(Publisher<HealthResult> publisher) throws Exception {
        CompletableFuture<HealthResult> future = new CompletableFuture<>();
        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(HealthResult result) {
                future.complete(result);
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        return future.get(15, TimeUnit.SECONDS);
    }

    /**
     * The injected {@link DataSource} bean is the {@code micronaut-data-jdbc} wrapper, whose
     * connections are context-managed: {@code close()} on one throws {@code NoConnectionException}
     * outside a {@code @Connectable}/{@code @Transactional} scope, so a test that acquired through
     * it would leak every connection it took instead of returning it to the pool (the same trap
     * {@link HikariPoolHealthIndicator} avoids by going through {@code ConnectionOperations}).
     * Resolving through {@link DataSourceResolver} unwraps it to the pool itself, which is what
     * this test needs: plain checkouts it can hold and then hand back.
     */
    private static DataSource rawPool(ApplicationContext context) {
        return context.findBean(DataSourceResolver.class)
                .orElse(DataSourceResolver.DEFAULT)
                .resolve(context.getBean(DataSource.class));
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }

    private static Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("apicurio.registry.url", registryUrl()),
                Map.entry("redis.uri", REDIS.getRedisURI()),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl() + "?stringtype=unspecified"),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.maximum-pool-size", MAX_POOL_SIZE),
                Map.entry("sbus.health.pool-acquire-timeout", "2s"),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("sbus.outbox.housekeeping-interval", "1h"),
                Map.entry("sbus.outbox.housekeeping-initial-delay", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
