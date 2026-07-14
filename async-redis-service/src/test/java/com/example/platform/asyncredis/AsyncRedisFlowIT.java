package com.example.platform.asyncredis;

import com.example.platform.asyncredis.dto.JobResponse;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end of the Kafka-free async->sync flow against a real Redis: POST /jobs enqueues on the
 * stream, the in-process worker processes it and releases the result, and the blocked request returns
 * 200 COMPLETED. Excluded from the default {@code test} task (run with {@code -PwithIT}).
 *
 * <p>Connects to the local Redis at {@code localhost:6379} (the default {@code redis.uri}), which is
 * also where CI's Redis service listens — so no Docker daemon is needed.
 */
@MicronautTest
@Property(name = "async.redis.wait-timeout", value = "5s")
@Property(name = "async.redis.process-latency-min-ms", value = "5")
@Property(name = "async.redis.process-latency-max-ms", value = "20")
class AsyncRedisFlowIT {

    @Inject
    @Client("/")
    HttpClient client;

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
