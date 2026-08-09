package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.dto.JobResponse;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RED-08: {@code POST /jobs} is idempotent. A retried submission must return the original job
 * instead of queuing a second one, and a key reused for a different payload must be a visible
 * conflict rather than a silent replay of unrelated work.
 */
@MicronautTest
@Property(name = "async.redis.security.enabled", value = "false")
@Property(name = "async.redis.stream", value = JobIdempotencyIT.STREAM)
@Property(name = "async.redis.group", value = "idem-it.workers")
@Property(name = "async.redis.wait-timeout", value = "200ms")
@Property(name = "async.redis.process-latency-min-ms", value = "400")
@Property(name = "async.redis.process-latency-max-ms", value = "400")
class JobIdempotencyIT {

    static final String STREAM = "idem-it.jobs";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    RedisClient redisClient;

    @Test
    void sameKeyAndPayloadReturnsTheOriginalJob() {
        String key = UUID.randomUUID().toString();
        SubmitJobRequest request = new SubmitJobRequest("IDEM-1", 1_500L, "same");

        String first = submit(request, key).jobId();
        String second = submit(request, key).jobId();

        assertEquals(first, second);
    }

    @Test
    void aReplayQueuesNoSecondJob() {
        String key = UUID.randomUUID().toString();
        SubmitJobRequest request = new SubmitJobRequest("IDEM-2", 2_500L, null);

        try (StatefulRedisConnection<String, String> conn = redisClient.connect()) {
            long before = conn.sync().xlen(STREAM);
            submit(request, key);
            submit(request, key);
            long after = conn.sync().xlen(STREAM);

            assertEquals(1L, after - before, "a replayed submission must not enqueue a second job");
        }
    }

    @Test
    void sameKeyWithADifferentPayloadIsAConflictNamingTheOriginalJob() {
        String key = UUID.randomUUID().toString();
        String original = submit(new SubmitJobRequest("IDEM-3", 3_000L, null), key).jobId();

        HttpClientResponseException conflict = assertThrows(HttpClientResponseException.class,
                () -> submit(new SubmitJobRequest("IDEM-3", 9_999L, null), key));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        JobResponse body = conflict.getResponse().getBody(JobResponse.class).orElseThrow();
        assertEquals("CONFLICT", body.status());
        assertEquals(original, body.jobId());
    }

    @Test
    void aConflictQueuesNothing() {
        String key = UUID.randomUUID().toString();
        try (StatefulRedisConnection<String, String> conn = redisClient.connect()) {
            submit(new SubmitJobRequest("IDEM-4", 4_000L, null), key);
            long afterOriginal = conn.sync().xlen(STREAM);

            assertThrows(HttpClientResponseException.class,
                    () -> submit(new SubmitJobRequest("IDEM-4", 8_888L, null), key));

            assertEquals(afterOriginal, conn.sync().xlen(STREAM),
                    "a rejected submission must not reach the stream");
        }
    }

    @Test
    void distinctKeysGetDistinctJobs() {
        SubmitJobRequest request = new SubmitJobRequest("IDEM-5", 5_000L, "shared payload");

        String first = submit(request, UUID.randomUUID().toString()).jobId();
        String second = submit(request, UUID.randomUUID().toString()).jobId();

        assertNotEquals(first, second);
    }

    @Test
    void aSubmissionWithNoKeyIsAcceptedWhileIdempotencyIsOptional() {
        JobResponse response = client.toBlocking().retrieve(
                HttpRequest.POST("/jobs", new SubmitJobRequest("IDEM-6", 6_000L, null)),
                JobResponse.class);

        assertEquals("PROCESSING", response.status());
    }

    private JobResponse submit(SubmitJobRequest request, String idempotencyKey) {
        return client.toBlocking().retrieve(
                HttpRequest.POST("/jobs", request).header("Idempotency-Key", idempotencyKey),
                JobResponse.class);
    }
}
