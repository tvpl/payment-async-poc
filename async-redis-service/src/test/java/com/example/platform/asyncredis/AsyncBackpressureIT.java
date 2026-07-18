package com.example.platform.asyncredis;

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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves admission backpressure: with the limiter set to 1 request/second, a burst of concurrent
 * POSTs sheds load with HTTP 429 instead of piling onto the workers. Connects to the local Redis at
 * {@code localhost:6379} (default, and where CI's Redis service listens).
 */
@MicronautTest
@Property(name = "async.redis.wait-timeout", value = "300ms")
@Property(name = "async.redis.admission-limit-per-sec", value = "1")
class AsyncBackpressureIT {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void burstIsThrottledWith429() throws Exception {
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger throttled = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        try {
            Future<?>[] futures = new Future<?>[n];
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures[i] = pool.submit(() -> {
                    try {
                        client.toBlocking().exchange(HttpRequest.POST("/jobs",
                                new SubmitJobRequest("REF-" + idx, 100L, null)));
                        accepted.incrementAndGet();
                    } catch (HttpClientResponseException e) {
                        if (e.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                            throttled.incrementAndGet();
                        }
                    }
                });
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(throttled.get() >= 1,
                "expected at least one 429 under a 1/s limit; throttled=" + throttled.get()
                        + " accepted=" + accepted.get());
    }
}
