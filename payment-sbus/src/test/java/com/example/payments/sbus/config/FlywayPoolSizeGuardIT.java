package com.example.payments.sbus.config;

import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the {@link FlywayPoolSizeGuard} wiring, not just its pure validation logic: against a
 * REAL, reachable Postgres with Flyway enabled and {@code maximum-pool-size=1} — the exact
 * configuration that used to self-deadlock for the full 30s connection-timeout (see
 * {@code HikariPoolHealthIndicatorIT}'s own javadoc for that root-cause writeup) — application
 * startup must now fail in well under that budget, with a message naming Flyway, because the
 * guard fires before the pool (and therefore Flyway) is ever created. Postgres is fully up and
 * reachable the entire time: the failure is proactive config rejection, not a discovery/
 * connectivity problem.
 */
@Testcontainers
class FlywayPoolSizeGuardIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @Test
    void rejectsAPoolOfOneBeforeFlywayEverGetsTheChanceToSelfDeadlock() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        try {
            long start = System.nanoTime();
            try {
                ApplicationContext.run(properties()).close();
                fail("expected startup to fail for a pool too small for Flyway");
            } catch (RuntimeException failure) {
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
                assertTrue(elapsedMillis < 10_000,
                        "the guard must fail fast, not wait out Hikari's own connection-timeout; took "
                                + elapsedMillis + "ms");
                assertTrue(causeChainContainsTheGuardsMessage(failure),
                        "expected FlywayPoolSizeGuard's message somewhere in the cause chain, got: "
                                + failure);
            }
        } finally {
            POSTGRES.stop();
            KAFKA.stop();
            APICURIO.stop();
            REDIS.stop();
        }
    }

    private static boolean causeChainContainsTheGuardsMessage(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConfigurationException && cause.getMessage() != null
                    && cause.getMessage().contains("Flyway")) {
                return true;
            }
        }
        return false;
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
                Map.entry("datasources.default.maximum-pool-size", 1),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("sbus.outbox.housekeeping-interval", "1h"),
                Map.entry("sbus.outbox.housekeeping-initial-delay", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
