package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.dto.JobResponse;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RED-01: polling has to tell four situations apart — a job that was never accepted, one still in
 * flight, one that finished, and one that finished so long ago its result is gone. Collapsing any
 * pair of them is what made the previous implementation answer {@code UNKNOWN} for work it had
 * accepted moments earlier.
 */
@MicronautTest
@Property(name = "async.redis.security.enabled", value = "false")
@Property(name = "async.redis.stream", value = "polling-it.jobs")
@Property(name = "async.redis.group", value = "polling-it.workers")
@Property(name = "async.redis.wait-timeout", value = "200ms")
@Property(name = "async.redis.result-ttl", value = "1s")
@Property(name = "async.redis.status-ttl", value = "30s")
// AUD-20: status-ttl must be >= idempotency-ttl; the 24h default would otherwise fail this
// context's own startup validation now that it exists.
@Property(name = "async.redis.idempotency-ttl", value = "30s")
@Property(name = "async.redis.process-latency-min-ms", value = "600")
@Property(name = "async.redis.process-latency-max-ms", value = "600")
class JobPollingIT {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void aJobThatWasNeverAcceptedIsUnknown() {
        String jobId = UUID.randomUUID().toString();

        HttpClientResponseException notFound = assertThrows(() ->
                client.toBlocking().exchange(HttpRequest.GET("/jobs/" + jobId), JobResponse.class));

        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatus());
        assertEquals("UNKNOWN", notFound.getResponse().getBody(JobResponse.class).orElseThrow().status());
    }

    @Test
    void anAcceptedJobIsProcessingWhileItIsStillInFlight() {
        // The worker takes 600ms; the POST gives up waiting after 200ms. The job is unquestionably
        // still in flight when the poll happens, so PROCESSING is the only honest answer.
        JobResponse submitted = submit(new SubmitJobRequest("POLL-PROCESSING", 4_200L, null));
        assertEquals("PROCESSING", submitted.status());

        HttpResponse<JobResponse> polled = client.toBlocking().exchange(
                HttpRequest.GET("/jobs/" + submitted.jobId()), JobResponse.class);

        assertEquals(HttpStatus.ACCEPTED, polled.getStatus());
        assertEquals("PROCESSING", polled.body().status());
        assertNull(polled.body().result());
    }

    @Test
    void aFinishedJobIsCompletedWithItsResult() {
        JobResponse submitted = submit(new SubmitJobRequest("POLL-COMPLETED", 10_000L, "note"));

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            HttpResponse<JobResponse> polled = client.toBlocking().exchange(
                    HttpRequest.GET("/jobs/" + submitted.jobId()), JobResponse.class);
            assertEquals(HttpStatus.OK, polled.getStatus());
            assertEquals("COMPLETED", polled.body().status());
            assertNotNull(polled.body().result());
            assertEquals("POLL-COMPLETED", polled.body().result().reference());
            assertEquals(10_000L, polled.body().result().amountCents());
            assertEquals(200L, polled.body().result().feeCents());
        });
    }

    @Test
    void aFinishedJobWhoseResultAgedOutIsExpiredNotUnknown() {
        // result-ttl is 1s and status-ttl is 30s: the gap between them is exactly the window in
        // which "this finished, but the payload is gone" is still an answerable fact.
        JobResponse submitted = submit(new SubmitJobRequest("POLL-EXPIRED", 7_000L, null));

        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            HttpClientResponseException gone = assertThrows(() -> client.toBlocking().exchange(
                    HttpRequest.GET("/jobs/" + submitted.jobId()), JobResponse.class));
            assertEquals(HttpStatus.GONE, gone.getStatus());
            assertEquals("EXPIRED",
                    gone.getResponse().getBody(JobResponse.class).orElseThrow().status());
        });
    }

    private JobResponse submit(SubmitJobRequest request) {
        return client.toBlocking().retrieve(HttpRequest.POST("/jobs", request), JobResponse.class);
    }

    private static HttpClientResponseException assertThrows(Runnable call) {
        return org.junit.jupiter.api.Assertions.assertThrows(HttpClientResponseException.class,
                call::run);
    }
}
