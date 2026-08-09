package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.dto.JobResponse;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RED-08 end to end: with the production gates switched on, {@code POST /jobs} demands a credential
 * and an {@code Idempotency-Key}, and {@code GET /jobs/{id}} is behind the same credential.
 */
@MicronautTest
@Property(name = "async.redis.security.enabled", value = "true")
@Property(name = "async.redis.security.api-keys[0]", value = JobAcceptanceGatesIT.API_KEY)
@Property(name = "async.redis.idempotency-required", value = "true")
@Property(name = "async.redis.stream", value = "gates-it.jobs")
@Property(name = "async.redis.group", value = "gates-it.workers")
@Property(name = "async.redis.wait-timeout", value = "200ms")
@Property(name = "async.redis.process-latency-min-ms", value = "400")
@Property(name = "async.redis.process-latency-max-ms", value = "400")
class JobAcceptanceGatesIT {

    static final String API_KEY = "gates-it-only-key";

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void submissionWithoutACredentialIsRejected() {
        HttpClientResponseException rejected = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.POST("/jobs", request())
                        .header("Idempotency-Key", UUID.randomUUID().toString())));

        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatus());
    }

    @Test
    void submissionWithAnUnknownCredentialIsRejected() {
        HttpClientResponseException rejected = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.POST("/jobs", request())
                        .header("X-API-Key", "not-the-configured-key")
                        .header("Idempotency-Key", UUID.randomUUID().toString())));

        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatus());
    }

    @Test
    void pollingWithoutACredentialIsRejected() {
        HttpClientResponseException rejected = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.GET("/jobs/" + UUID.randomUUID())));

        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatus());
    }

    @Test
    void submissionWithAValidCredentialAndKeyIsAccepted() {
        JobResponse accepted = client.toBlocking().retrieve(
                HttpRequest.POST("/jobs", request())
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                JobResponse.class);

        assertEquals("PROCESSING", accepted.status());
        assertNotNull(accepted.jobId());
    }

    @Test
    void submissionWithoutAnIdempotencyKeyIsRejectedWhenTheGateIsOn() {
        HttpClientResponseException rejected = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/jobs", request()).header("X-API-Key", API_KEY)));

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED",
                rejected.getResponse().getBody(JobResponse.class).orElseThrow().status());
    }

    private static SubmitJobRequest request() {
        return new SubmitJobRequest("GATES-1", 1_000L, null);
    }
}
