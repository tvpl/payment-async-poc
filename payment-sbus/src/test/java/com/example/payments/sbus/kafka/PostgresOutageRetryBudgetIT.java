package com.example.payments.sbus.kafka;

import com.example.payments.common.events.Topics;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task_T37 (SCAL-01): with Postgres persistently unreachable, {@link RetryPublisher}'s own
 * durable-write attempt must fail FAST — never block anywhere near the old 30-minute budget — so
 * the consume loop (which retries this call inline via {@code @ErrorStrategy}, see
 * {@link PaymentRequestedConsumer}'s javadoc) never holds its partition hostage for minutes during
 * a prolonged outage. Uses a datasource URL that refuses the TCP connection immediately (a closed
 * local port) instead of pausing a live Postgres container, whose SIGSTOP would leave in-flight
 * connections hanging at the socket level rather than failing fast — the wrong shape of outage for
 * proving THIS property. No Kafka listener or rebalance is exercised (deliberately, per the task's
 * own "sem rebalance forçado no teste") — this proves the underlying mechanism the listener's
 * short {@code @ErrorStrategy} budget (see {@code ConsumerErrorStrategyUnitTest}) relies on.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresOutageRetryBudgetIT {

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    private ApplicationContext context;
    private RetryPublisher retryPublisher;

    @BeforeAll
    void start() {
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        retryPublisher = context.getBean(RetryPublisher.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    @Test
    void aPersistentlyUnreachablePostgresFailsFastInsteadOfHoldingThePartitionForMinutes() {
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(Topics.REQUESTED, 0, 1L, "outage-key", new byte[]{1, 2, 3});

        long startNanos = System.nanoTime();
        assertThrows(RuntimeException.class, () -> retryPublisher.scheduleFirstRetry(
                Topics.REQUESTED, record, Map.of(), new RuntimeException("simulated transient failure")));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        // Comfortably under max.poll.interval.ms (SCAL-01's 5-minute ceiling) and, in practice,
        // under HikariCP's own connection-acquisition timeout for a connection actively refused.
        assertTrue(elapsed.compareTo(Duration.ofMinutes(1)) < 0,
                "a durable-write attempt against an unreachable Postgres must fail fast, not hold "
                        + "the caller (and therefore the consumer's partition) for minutes: took " + elapsed);
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }

    private static Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("apicurio.registry.url", registryUrl()),
                Map.entry("redis.uri", REDIS.getRedisURI()),
                // Refused immediately (nothing listens on port 1 locally) instead of a live
                // Postgres being paused/blackholed, which would hang at the socket level.
                Map.entry("datasources.default.url", "jdbc:postgresql://127.0.0.1:1/sbus?stringtype=unspecified"),
                Map.entry("datasources.default.username", "sbus"),
                Map.entry("datasources.default.password", "sbus"),
                // Negative: HikariCP skips its own eager "get one connection at pool startup"
                // check, so the DataSource bean (and ApplicationContext.run() itself) constructs
                // successfully despite Postgres being unreachable — the FIRST real query, inside
                // the test body below, is what must fail fast, not bean construction in @BeforeAll.
                Map.entry("datasources.default.initialization-fail-timeout", -1),
                // Short and explicit so a refused-connection attempt gives up quickly and
                // deterministically rather than riding HikariCP's own 30s default.
                Map.entry("datasources.default.connection-timeout", 5000),
                Map.entry("flyway.datasources.default.enabled", false),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
