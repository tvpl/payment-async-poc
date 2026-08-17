package com.example.payments.api.coordination;

import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.model.SimulationStatus;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micronaut.context.ApplicationContext;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCAL-05: with the correlation channel split into {@code N} shards, a waiter must still wake up
 * correctly regardless of which shard its own requestId happens to hash onto - and, during the
 * transition, an instance still subscribed only to the legacy unsharded channel must keep
 * receiving wake-ups too.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseCoordinatorShardingIT {

    private static final int SHARDS = 2;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(3);

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private ApplicationContext context;
    private ResponseCoordinator coordinator;
    private RedisStatusStore store;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        context = ApplicationContext.run(Map.of(
                "micronaut.server.enabled", false,
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", REDIS.getRedisURI(),
                "apicurio.registry.url", registryUrl(),
                "otel.traces.exporter", "none",
                "payment.simulation.wait-timeout", WAIT_TIMEOUT.toString(),
                "payment.simulation.response-channel-shards", SHARDS));
        coordinator = context.getBean(ResponseCoordinator.class);
        store = context.getBean(RedisStatusStore.class);
    }

    @AfterAll
    void stop() {
        context.close();
    }

    /** Done-when: "IT com N=2: waiters em shards distintos acordam corretamente". */
    @Test
    void waitersOnDistinctShardsBothWakeUpCorrectly() throws Exception {
        String requestIdShard0 = findRequestIdOnShard(0);
        String requestIdShard1 = findRequestIdOnShard(1);
        StatusEntry terminal0 = new StatusEntry(requestIdShard0, SimulationStatus.COMPLETED, null);
        StatusEntry terminal1 = new StatusEntry(requestIdShard1, SimulationStatus.FAILED, null);

        CompletableFuture<StatusEntry> future0 = coordinator.register(requestIdShard0);
        CompletableFuture<StatusEntry> future1 = coordinator.register(requestIdShard1);

        store.save(terminal0);
        store.publishResponse(requestIdShard0);
        store.save(terminal1);
        store.publishResponse(requestIdShard1);

        assertEquals(terminal0, future0.get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        assertEquals(terminal1, future1.get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
    }

    /**
     * Done-when: "Publicação também no canal legado durante a transição (flag)". A subscriber
     * that only watches the legacy, unsharded channel (standing in for an instance not yet
     * upgraded to shard-aware subscription) must still receive the wake-up.
     */
    @Test
    void publishAlsoReachesASubscriberWatchingOnlyTheLegacyChannel() throws Exception {
        String requestId = "req-legacy-" + UUID.randomUUID();
        SynchronousQueue<String> received = new SynchronousQueue<>();
        RedisClient legacyClient = RedisClient.create(RedisURI.create(REDIS.getRedisURI()));
        StatefulRedisPubSubConnection<String, String> legacyOnly = legacyClient.connectPubSub();
        try {
            legacyOnly.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    received.offer(message);
                }
            });
            legacyOnly.sync().subscribe("payment-sim-responses");

            store.save(new StatusEntry(requestId, SimulationStatus.COMPLETED, null));
            store.publishResponse(requestId);

            String message = received.poll(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertTrue(message != null, "legacy-only subscriber never received the publish");
            assertEquals(requestId, message);
        } finally {
            legacyOnly.close();
            legacyClient.shutdown();
        }
    }

    /** Deterministically finds a requestId whose shard (given {@link #SHARDS}) is {@code shard}. */
    private static String findRequestIdOnShard(int shard) {
        for (int i = 0; i < 1000; i++) {
            String candidate = "req-" + UUID.randomUUID();
            if (ResponseChannels.shardFor(candidate, SHARDS) == shard) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a requestId hashing to shard " + shard);
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
