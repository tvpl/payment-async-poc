package com.example.platform.asyncredis.queue;

import com.example.platform.asyncredis.dto.JobResponse;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RED-02 / CAP-03 at the HTTP boundary: an exhausted wait pool answers with explicit backpressure
 * rather than blocking past the budget or dropping the work. The job itself was accepted and
 * enqueued, so the honest answer is {@code 202 PROCESSING} plus a header naming the saturation —
 * a {@code 429} would claim the submission was refused, which is not what happened.
 *
 * <p>Admission refusal ({@code 429}, nothing enqueued) is the other half of the contract and is
 * covered by {@code AsyncBackpressureIT}.
 *
 * <p>The pool of one is saturated by the test itself; workers use dedicated connections, so the job
 * still gets processed while no request can wait on it.
 */
@MicronautTest
@Property(name = "async.redis.security.enabled", value = "false")
@Property(name = "async.redis.stream", value = "bp-it.jobs")
@Property(name = "async.redis.group", value = "bp-it.workers")
@Property(name = "async.redis.pool-max-total", value = "1")
@Property(name = "async.redis.pool-max-wait", value = "200ms")
@Property(name = "async.redis.wait-timeout", value = "1s")
@Property(name = "async.redis.process-latency-min-ms", value = "5")
@Property(name = "async.redis.process-latency-max-ms", value = "20")
class JobBackpressureContractIT {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    RedisConnections redis;

    @Test
    void anExhaustedWaitPoolAnswers202WithExplicitBackpressure() throws Exception {
        try (Saturation ignored = new Saturation(redis)) {
            HttpResponse<JobResponse> response = client.toBlocking().exchange(
                    HttpRequest.POST("/jobs", new SubmitJobRequest("BP-1", 7_000L, null)),
                    JobResponse.class);

            assertEquals(HttpStatus.ACCEPTED, response.getStatus());
            assertEquals("wait-pool-exhausted", response.header("X-Backpressure"));
            assertEquals("1", response.header("Retry-After"));

            JobResponse body = response.body();
            assertNotNull(body);
            assertNotNull(body.jobId());
            assertEquals("PROCESSING", body.status());
            assertEquals("/jobs/" + body.jobId(), body.statusUrl());
            assertNull(body.result(), "a shed wait has no result to report yet");
        }
    }

    @Test
    void aJobShedByWaitCapacityIsStillProcessedToCompletion() throws Exception {
        String jobId;
        try (Saturation ignored = new Saturation(redis)) {
            HttpResponse<JobResponse> response = client.toBlocking().exchange(
                    HttpRequest.POST("/jobs", new SubmitJobRequest("BP-2", 5_000L, null)),
                    JobResponse.class);

            assertEquals(HttpStatus.ACCEPTED, response.getStatus());
            assertEquals("wait-pool-exhausted", response.header("X-Backpressure"));
            jobId = response.body().jobId();
            assertNotNull(jobId);
        }

        // Backpressure sheds the *wait*, never the work: the enqueued job must still finish.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            HttpResponse<JobResponse> polled = client.toBlocking().exchange(
                    HttpRequest.GET("/jobs/" + jobId), JobResponse.class);
            assertEquals(HttpStatus.OK, polled.getStatus());
            JobResponse body = polled.body();
            assertEquals("COMPLETED", body.status());
            assertNotNull(body.result());
            assertEquals("BP-2", body.result().reference());
            assertEquals(5_000L, body.result().amountCents());
            assertEquals(100L, body.result().feeCents()); // 2% of 5_000
            assertEquals("PROCESSED", body.result().status());
        });
    }

    /** Holds the pool's only wait connection for as long as it stays open. */
    private static final class Saturation implements AutoCloseable {

        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread thread;

        private Saturation(RedisConnections redis) throws InterruptedException {
            CountDownLatch held = new CountDownLatch(1);
            this.thread = new Thread(() -> {
                try (RedisConnections.WaitLease lease = redis.acquireWait(5_000)) {
                    held.countDown();
                    release.await(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, "backpressure-saturation");
            this.thread.setDaemon(true);
            this.thread.start();
            if (!held.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("could not saturate the wait pool");
            }
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            thread.join(10_000);
        }
    }
}
