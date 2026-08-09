package com.example.platform.asyncredis.queue;

import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-02: the wait pool is bounded and acquiring from it is part of the request's budget, not extra
 * time on top of it. With {@code pool-max-total} at 1, one held connection is a fully saturated pool,
 * which is what makes the budget arithmetic observable.
 */
@MicronautTest
@Property(name = "async.redis.security.enabled", value = "false")
@Property(name = "async.redis.stream", value = "budget-it.jobs")
@Property(name = "async.redis.group", value = "budget-it.workers")
@Property(name = "async.redis.pool-max-total", value = "1")
@Property(name = "async.redis.wait-timeout", value = "1s")
class JobWaitBudgetIT {

    private static final long BUDGET_MS = 1_000;

    @Inject
    JobQueue queue;

    @Inject
    RedisConnections redis;

    @Test
    void aReleasedResultIsReturnedToTheWaiter() {
        String jobId = UUID.randomUUID().toString();
        JobResult released = new JobResult(jobId, "BUDGET-1", 10_000L, 200L, "PROCESSED", "test", 1L);

        queue.release(released);
        WaitOutcome outcome = queue.awaitResult(jobId);

        JobResult result = assertInstanceOf(WaitOutcome.Released.class, outcome).result();
        assertEquals(jobId, result.jobId());
        assertEquals("BUDGET-1", result.reference());
        assertEquals(10_000L, result.amountCents());
        assertEquals(200L, result.feeCents());
        assertEquals("PROCESSED", result.status());
    }

    @Test
    void everyWaitReturnsItsConnectionSoCapacitySurvivesRepeatedUse() {
        // A wait that borrows without releasing burns one slot of a bounded pool permanently, so with
        // a capacity of one the *second* wait would already find nothing left. Three timed-out waits
        // followed by a successful one is what separates "bounded" from "leaking".
        for (int i = 0; i < 3; i++) {
            WaitOutcome outcome = queue.awaitResult(UUID.randomUUID().toString());
            assertInstanceOf(WaitOutcome.TimedOut.class, outcome, "wait " + i + " lost pool capacity");
            assertEquals(0, redis.borrowedConnections(), "wait " + i + " did not return its connection");
        }

        String jobId = UUID.randomUUID().toString();
        queue.release(new JobResult(jobId, "REUSE-1", 4_000L, 80L, "PROCESSED", "test", 2L));

        JobResult result = assertInstanceOf(WaitOutcome.Released.class, queue.awaitResult(jobId)).result();
        assertEquals(jobId, result.jobId());
        assertEquals("REUSE-1", result.reference());
        assertEquals(4_000L, result.amountCents());
        assertEquals(80L, result.feeCents());
        assertEquals("PROCESSED", result.status());
        assertEquals(0, redis.borrowedConnections(), "the final wait did not return its connection");
    }

    @Test
    void aWaitWithNoResultEndsInsideItsOwnBudget() {
        long start = System.nanoTime();
        WaitOutcome outcome = queue.awaitResult(UUID.randomUUID().toString());
        long elapsed = millisSince(start);

        assertInstanceOf(WaitOutcome.TimedOut.class, outcome);
        assertTrue(elapsed >= BUDGET_MS * 0.8, "must actually wait its budget; elapsed=" + elapsed);
        assertTrue(elapsed < BUDGET_MS * 2, "must not exceed its budget; elapsed=" + elapsed);
    }

    @Test
    void aSaturatedPoolSheddsTheWaitInsteadOfQueueingBehindIt() throws Exception {
        // The only connection stays out for far longer than the budget, so this request can never
        // acquire one. It must give up at the budget, not park until a connection frees up.
        try (Holder holder = holdTheOnlyConnection(3_000)) {
            holder.awaitHeld();

            long start = System.nanoTime();
            WaitOutcome outcome = queue.awaitResult(UUID.randomUUID().toString());
            long elapsed = millisSince(start);

            assertInstanceOf(WaitOutcome.NoCapacity.class, outcome);
            assertTrue(elapsed < BUDGET_MS * 1.5, "shedding must happen at the budget; elapsed=" + elapsed);
        }
    }

    @Test
    void theBudgetCoversAcquisitionAndNotOnlyTheBrpop() throws Exception {
        // The connection frees up after 600ms, leaving ~400ms of the 1s budget for the pop. If
        // acquisition were free, this would run 600ms + a full 1s pop = ~1.6s: the exact doubling
        // RED-02 forbids.
        try (Holder holder = holdTheOnlyConnection(600)) {
            holder.awaitHeld();

            long start = System.nanoTime();
            WaitOutcome outcome = queue.awaitResult(UUID.randomUUID().toString());
            long elapsed = millisSince(start);

            assertInstanceOf(WaitOutcome.TimedOut.class, outcome);
            assertTrue(elapsed < BUDGET_MS * 1.4,
                    "acquisition must be spent from the same budget; elapsed=" + elapsed);
        }
    }

    @Test
    void concurrentWaitsNeverCheckOutMoreConnectionsThanTheCapacity() throws Exception {
        int capacity = redis.poolCapacity();
        assertEquals(1, capacity, "this test is written against a pool of one");

        ExecutorService pool = Executors.newFixedThreadPool(6);
        AtomicInteger highWaterMark = new AtomicInteger();
        try {
            Future<?>[] waits = new Future<?>[6];
            for (int i = 0; i < waits.length; i++) {
                waits[i] = pool.submit(() -> queue.awaitResult(UUID.randomUUID().toString()));
            }
            long until = System.currentTimeMillis() + BUDGET_MS;
            while (System.currentTimeMillis() < until) {
                highWaterMark.accumulateAndGet(redis.borrowedConnections(), Math::max);
            }
            for (Future<?> wait : waits) {
                wait.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(highWaterMark.get() <= capacity,
                "borrowed " + highWaterMark.get() + " connections for a capacity of " + capacity);
        assertEquals(0, redis.borrowedConnections(), "every wait must return its connection");
    }

    private Holder holdTheOnlyConnection(long holdMillis) {
        return new Holder(redis, holdMillis);
    }

    /** Keeps the pool's single connection checked out for a fixed time. */
    private static final class Holder implements AutoCloseable {

        private final CountDownLatch held = new CountDownLatch(1);
        private final Thread thread;

        private Holder(RedisConnections redis, long holdMillis) {
            this.thread = new Thread(() -> {
                try (RedisConnections.WaitLease lease = redis.acquireWait(5_000)) {
                    held.countDown();
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, "wait-pool-holder");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void awaitHeld() throws InterruptedException {
            assertTrue(held.await(5, TimeUnit.SECONDS), "holder never acquired the connection");
        }

        @Override
        public void close() throws InterruptedException {
            thread.join(10_000);
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
