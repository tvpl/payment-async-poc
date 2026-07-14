package com.example.platform.asyncredis;

import com.example.platform.asyncredis.dto.SubmitJobRequest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves poison protection: a job the worker always fails is redelivered until it exceeds
 * {@code max-deliveries} and is then moved to the dead-letter stream instead of looping forever.
 * Uses a fast reclaim cadence and the {@code fail-on-reference} test hook. Connects to the local
 * Redis at {@code localhost:6379} (the default, and where CI's Redis service listens).
 */
@MicronautTest
@Property(name = "async.redis.wait-timeout", value = "500ms")
@Property(name = "async.redis.fail-on-reference", value = "POISON")
@Property(name = "async.redis.max-deliveries", value = "1")
@Property(name = "async.redis.reclaim-idle", value = "100ms")
@Property(name = "async.redis.reclaim-interval", value = "150ms")
@Property(name = "async.redis.dlq-stream", value = AsyncDlqIT.DLQ)
class AsyncDlqIT {

    static final String DLQ = "async.jobs.dlq";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    RedisClient redisClient;

    @Test
    void poisonJobEndsUpInDeadLetterStream() {
        // The worker keeps failing this job; after > max-deliveries it should be dead-lettered.
        client.toBlocking().exchange(HttpRequest.POST("/jobs",
                new SubmitJobRequest("POISON", 1000L, "boom")));

        try (StatefulRedisConnection<String, String> conn = redisClient.connect()) {
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertTrue(conn.sync().xlen(DLQ) >= 1, "poison job should be in the DLQ"));
        }
    }
}
