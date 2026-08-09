package com.example.platform.asyncredis.queue;

import com.example.platform.asyncredis.redis.RedisConnections;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-02: {@code pool-max-wait} is a finite acquisition timeout in its own right, not just a synonym
 * for the request budget. With a 3s wait budget and a 200ms acquisition cap, a saturated pool must
 * shed at ~200ms — spending the whole 3s queueing for a connection would leave no budget at all for
 * the pop the caller is actually paying for.
 */
@MicronautTest
@Property(name = "async.redis.security.enabled", value = "false")
@Property(name = "async.redis.stream", value = "cap-it.jobs")
@Property(name = "async.redis.group", value = "cap-it.workers")
@Property(name = "async.redis.pool-max-total", value = "1")
@Property(name = "async.redis.wait-timeout", value = "3s")
@Property(name = "async.redis.pool-max-wait", value = "200ms")
class JobWaitAcquisitionCapIT {

    private static final long ACQUIRE_CAP_MS = 200;
    private static final long WAIT_BUDGET_MS = 3_000;

    @Inject
    JobQueue queue;

    @Inject
    RedisConnections redis;

    @Test
    void acquisitionIsCappedByPoolMaxWaitAndNotByTheWholeBudget() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (RedisConnections.WaitLease lease = redis.acquireWait(5_000)) {
                held.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "cap-holder");
        holder.setDaemon(true);
        holder.start();

        try {
            assertTrue(held.await(5, TimeUnit.SECONDS), "holder never acquired the connection");

            long start = System.nanoTime();
            WaitOutcome outcome = queue.awaitResult(UUID.randomUUID().toString());
            long elapsed = (System.nanoTime() - start) / 1_000_000L;

            assertInstanceOf(WaitOutcome.NoCapacity.class, outcome);
            assertTrue(elapsed >= ACQUIRE_CAP_MS * 0.5,
                    "must actually wait the acquisition cap; elapsed=" + elapsed);
            assertTrue(elapsed < WAIT_BUDGET_MS / 2,
                    "acquisition must stop at pool-max-wait, not run to the budget; elapsed=" + elapsed);
        } finally {
            release.countDown();
            holder.join(10_000);
        }
    }
}
