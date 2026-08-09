package com.example.payments.api.coordination;

import com.example.payments.api.config.ApiProperties;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.model.SimulationStatus;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The waiter must end on a result, a timeout, an interruption or shutdown, and every one of
 * those paths must leave the local registry empty (PAY-10).
 */
class ResponseCoordinatorUnitTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofMillis(300);

    private RedisStatusStore store;
    private ResponseCoordinator coordinator;

    @BeforeEach
    void setUp() {
        store = mock(RedisStatusStore.class);
        ApiProperties properties = new ApiProperties();
        properties.setWaitTimeout(WAIT_TIMEOUT);
        coordinator = new ResponseCoordinator(mock(RedisClient.class), store, properties);
    }

    @Test
    void aResultEndsTheWaitAndRemovesTheWaiter() {
        String requestId = "req-result";
        StatusEntry terminal = new StatusEntry(requestId, SimulationStatus.COMPLETED, null);
        CompletableFuture<StatusEntry> future = coordinator.register(requestId);
        future.complete(terminal);

        Optional<StatusEntry> result = coordinator.await(requestId, future);

        assertEquals(Optional.of(terminal), result);
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void aTimeoutEndsTheWaitWithinItsBudgetAndRemovesTheWaiter() {
        String requestId = "req-timeout";
        CompletableFuture<StatusEntry> future = coordinator.register(requestId);

        long start = System.nanoTime();
        Optional<StatusEntry> result = coordinator.await(requestId, future);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(Optional.empty(), result);
        assertEquals(0, coordinator.pendingCount());
        assertTrue(elapsed.compareTo(WAIT_TIMEOUT) >= 0, "returned before its budget: " + elapsed);
        assertTrue(elapsed.compareTo(WAIT_TIMEOUT.multipliedBy(10)) < 0,
                "wait exceeded its budget: " + elapsed);
    }

    @Test
    void anInterruptionEndsTheWaitAndRemovesTheWaiter() throws InterruptedException {
        String requestId = "req-interrupt";
        CompletableFuture<StatusEntry> future = coordinator.register(requestId);
        AtomicReference<Optional<StatusEntry>> result = new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            waiting.countDown();
            result.set(coordinator.await(requestId, future));
            interruptFlagRestored.set(Thread.currentThread().isInterrupted());
            done.countDown();
        });
        waiter.start();
        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        waiter.interrupt();

        assertTrue(done.await(2, TimeUnit.SECONDS), "interrupted wait never returned");
        assertEquals(Optional.empty(), result.get());
        assertTrue(interruptFlagRestored.get(), "interrupt status must be restored");
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void shutdownReleasesEveryWaiterAndClearsTheLocalRegistry() {
        coordinator.register("req-a");
        coordinator.register("req-b");
        coordinator.register("req-c");

        coordinator.close();

        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void shutdownEndsAnInFlightWaitWithoutBurningTheRemainingBudget() throws InterruptedException {
        ApiProperties longWait = new ApiProperties();
        longWait.setWaitTimeout(Duration.ofSeconds(30));
        ResponseCoordinator patient =
                new ResponseCoordinator(mock(RedisClient.class), store, longWait);
        String requestId = "req-shutdown";
        CompletableFuture<StatusEntry> future = patient.register(requestId);
        AtomicReference<Optional<StatusEntry>> result = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            waiting.countDown();
            result.set(patient.await(requestId, future));
            done.countDown();
        });
        waiter.start();
        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        patient.close();

        assertTrue(done.await(5, TimeUnit.SECONDS), "shutdown did not release the waiter");
        assertEquals(Optional.empty(), result.get());
        assertEquals(0, patient.pendingCount());
    }

    @Test
    void registeringAfterShutdownLeavesNoWaiterBehind() {
        coordinator.close();

        CompletableFuture<StatusEntry> future = coordinator.register("req-late");

        assertTrue(future.isDone(), "a late waiter must not park against a closing API");
        assertEquals(Optional.empty(), coordinator.await("req-late", future));
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void aNonTerminalStatusIsNotAResultAndKeepsTheWaiterWaiting() {
        String requestId = "req-pending";
        coordinator.register(requestId);
        when(store.get(requestId))
                .thenReturn(Optional.of(new StatusEntry(requestId, SimulationStatus.PENDING, null)));

        coordinator.complete(requestId);

        assertEquals(1, coordinator.pendingCount());
        assertFalse(coordinator.register(requestId).isDone());
    }

    @Test
    void registeringTheSameRequestTwiceKeepsOneWaiter() {
        CompletableFuture<StatusEntry> first = coordinator.register("req-dup");
        CompletableFuture<StatusEntry> second = coordinator.register("req-dup");

        assertSame(first, second);
        assertEquals(1, coordinator.pendingCount());
    }
}
