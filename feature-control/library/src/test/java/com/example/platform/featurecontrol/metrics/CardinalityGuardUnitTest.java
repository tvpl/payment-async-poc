package com.example.platform.featurecontrol.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FTR-05: "alta cardinalidade sintética permanece bounded." */
class CardinalityGuardUnitTest {

    @Test
    void aValueWithinTheLimitIsAdmittedUnchanged() {
        CardinalityGuard guard = new CardinalityGuard(3);

        assertEquals("flag-a", guard.admit("flag-a"));
        assertEquals("flag-b", guard.admit("flag-b"));
        assertEquals(2, guard.size());
    }

    @Test
    void aRepeatedValueIsAlwaysAdmittedEvenAfterTheLimitIsFull() {
        CardinalityGuard guard = new CardinalityGuard(1);
        guard.admit("flag-a");

        assertEquals("flag-a", guard.admit("flag-a"), "an already-tracked value never collapses to overflow");
        assertEquals(1, guard.size());
    }

    @Test
    void aNewValueBeyondTheLimitCollapsesToOverflow() {
        CardinalityGuard guard = new CardinalityGuard(2);
        guard.admit("flag-a");
        guard.admit("flag-b");

        assertEquals(CardinalityGuard.OVERFLOW, guard.admit("flag-c"));
        assertEquals(2, guard.size(), "the tracked set never grows past the limit");
    }

    @Test
    void manyDistinctSyntheticValuesStayBoundedAtTheLimit() {
        CardinalityGuard guard = new CardinalityGuard(50);

        for (int i = 0; i < 10_000; i++) {
            guard.admit("synthetic-flag-" + i);
        }

        assertEquals(50, guard.size(), "10,000 distinct inputs must never grow tracked cardinality past the limit");
    }

    @Test
    void aNullValueIsTreatedAsOverflowNeverTracked() {
        CardinalityGuard guard = new CardinalityGuard(5);

        assertEquals(CardinalityGuard.OVERFLOW, guard.admit(null));
        assertEquals(0, guard.size());
    }

    @Test
    void concurrentAdmissionOfManyDistinctValuesStaysBounded() throws InterruptedException {
        CardinalityGuard guard = new CardinalityGuard(20);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger overflowCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            int base = t * 1000;
            pool.submit(() -> {
                try {
                    go.await();
                    for (int i = 0; i < 1000; i++) {
                        if (CardinalityGuard.OVERFLOW.equals(guard.admit("v-" + (base + i)))) {
                            overflowCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertTrue(guard.size() <= 20 + threads,
                "even under a race at the boundary, tracked size must stay close to the limit, not grow unbounded: was "
                        + guard.size());
        assertTrue(overflowCount.get() > 0, "most of 16,000 distinct concurrent values must have overflowed a limit of 20");
    }
}
