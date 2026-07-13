package com.example.platform.asyncredis;

import com.example.platform.asyncredis.dto.JobResponse;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end of the Kafka-free async->sync flow against a real Redis: POST /jobs enqueues on the
 * stream, the in-process worker processes it and releases the result, and the blocked request returns
 * 200 COMPLETED. Excluded from the default {@code test} task (run with {@code -PwithIT}).
 *
 * <p>Uses Testcontainers by default (needs Docker). Set {@code REDIS_TEST_URI} (e.g.
 * {@code redis://localhost:6379}) to run against an already-running Redis instead — handy in
 * environments without a Docker daemon or in CI with a Redis service.
 */
@MicronautTest
@Testcontainers
class AsyncRedisFlowIT implements TestPropertyProvider {

    private static final String EXTERNAL_URI = System.getenv("REDIS_TEST_URI");

    static final RedisContainer REDIS = EXTERNAL_URI == null
            ? new RedisContainer(DockerImageName.parse("redis:7-alpine"))
            : null;

    static {
        if (REDIS != null) {
            REDIS.start();
        }
    }

    private static String redisUri() {
        return EXTERNAL_URI != null ? EXTERNAL_URI : REDIS.getRedisURI();
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "redis.uri", redisUri(),
                "async.redis.wait-timeout", "5s",
                "async.redis.process-latency-min-ms", "5",
                "async.redis.process-latency-max-ms", "20");
    }

    @Test
    void submitReturnsCompletedWithResult() {
        SubmitJobRequest request = new SubmitJobRequest("ORDER-1", 10_000L, "hello");

        HttpResponse<JobResponse> response = client.toBlocking().exchange(
                HttpRequest.POST("/jobs", request), JobResponse.class);

        assertEquals(HttpStatus.OK, response.getStatus());
        JobResponse body = response.body();
        assertNotNull(body);
        assertEquals("COMPLETED", body.status());
        assertNotNull(body.result());
        assertEquals("ORDER-1", body.result().reference());
        assertEquals(10_000L, body.result().amountCents());
        assertEquals(200L, body.result().feeCents()); // 2% of 10_000
        assertEquals("PROCESSED", body.result().status());
    }

    @Test
    void getReflectsDurableResult() {
        SubmitJobRequest request = new SubmitJobRequest("ORDER-2", 5_000L, null);
        JobResponse submitted = client.toBlocking().retrieve(
                HttpRequest.POST("/jobs", request), JobResponse.class);
        assertNotNull(submitted.jobId());

        HttpResponse<JobResponse> got = client.toBlocking().exchange(
                HttpRequest.GET("/jobs/" + submitted.jobId()), JobResponse.class);
        assertEquals(HttpStatus.OK, got.getStatus());
        assertEquals("COMPLETED", got.body().status());
    }
}
