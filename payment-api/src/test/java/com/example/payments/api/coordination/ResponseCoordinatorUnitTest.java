package com.example.payments.api.coordination;

import com.example.payments.api.config.ApiProperties;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.model.SimulationStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    /**
     * task_3801253b regression: onMessage() is the Redis PubSub listener's entry point, which
     * Lettuce calls on the connection's single Netty event-loop thread. It must return
     * immediately regardless of how long the underlying Redis lookup takes — the old code called
     * {@link ResponseCoordinator#complete} inline there, so a slow/stuck store.get() blocked that
     * thread, and with it every other waiter's notification on the same instance.
     */
    @Test
    void onMessageDispatchesAsynchronouslyInsteadOfBlockingTheCallingThread() throws Exception {
        String requestId = "req-slow";
        StatusEntry terminal = new StatusEntry(requestId, SimulationStatus.COMPLETED, null);
        CountDownLatch releaseStore = new CountDownLatch(1);
        when(store.get(requestId)).thenAnswer(invocation -> {
            assertTrue(releaseStore.await(2, TimeUnit.SECONDS), "test never released the slow store call");
            return Optional.of(terminal);
        });
        CompletableFuture<StatusEntry> future = coordinator.register(requestId);

        long start = System.nanoTime();
        coordinator.onMessage(requestId);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(elapsed.compareTo(Duration.ofMillis(200)) < 0,
                "onMessage() blocked the calling thread for " + elapsed);
        releaseStore.countDown();
        assertEquals(terminal, future.get(2, TimeUnit.SECONDS));
    }

    /**
     * task_3801253b regression, the actual production symptom: with a shared Netty dispatch
     * thread, one waiter's stuck Redis lookup used to starve every other waiter's notification
     * too. Dispatching each onMessage() call to its own virtual thread means a still-blocked
     * lookup for one requestId must never delay another's completion.
     */
    @Test
    void aStuckNotificationForOneRequestNeverBlocksAnothers() throws Exception {
        String stuckRequestId = "req-stuck";
        String fastRequestId = "req-fast";
        StatusEntry fastTerminal = new StatusEntry(fastRequestId, SimulationStatus.COMPLETED, null);
        CountDownLatch releaseStuck = new CountDownLatch(1);
        when(store.get(stuckRequestId)).thenAnswer(invocation -> {
            releaseStuck.await(5, TimeUnit.SECONDS);
            return Optional.of(new StatusEntry(stuckRequestId, SimulationStatus.COMPLETED, null));
        });
        when(store.get(fastRequestId)).thenReturn(Optional.of(fastTerminal));
        coordinator.register(stuckRequestId);
        CompletableFuture<StatusEntry> fastFuture = coordinator.register(fastRequestId);

        coordinator.onMessage(stuckRequestId);
        coordinator.onMessage(fastRequestId);

        assertEquals(fastTerminal, fastFuture.get(1, TimeUnit.SECONDS),
                "a stuck notification for another request must not delay this one");
        releaseStuck.countDown();
    }

    /**
     * OBS-01: a lost pub/sub notification must not turn a finished result into a false timeout.
     * Nothing here ever calls {@code complete()}/{@code onMessage()} - only {@code await()}'s own
     * periodic re-poll of the store can surface the result, and the first re-poll deliberately
     * finds nothing so this proves the loop keeps checking rather than giving up after one look.
     */
    @Test
    void aRepollFindsATerminalResultThatArrivedWithoutAPublish() {
        ApiProperties longerWait = new ApiProperties();
        longerWait.setWaitTimeout(Duration.ofSeconds(2));
        ResponseCoordinator patient = new ResponseCoordinator(mock(RedisClient.class), store, longerWait);
        String requestId = "req-repoll";
        StatusEntry terminal = new StatusEntry(requestId, SimulationStatus.COMPLETED, null);
        CompletableFuture<StatusEntry> future = patient.register(requestId);
        when(store.get(requestId)).thenReturn(Optional.empty(), Optional.of(terminal));

        long start = System.nanoTime();
        Optional<StatusEntry> result = patient.await(requestId, future);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(Optional.of(terminal), result);
        verify(store, times(2)).get(requestId);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0,
                "result only surfaced at/after the full wait-timeout, not via re-poll: " + elapsed);
    }

    /**
     * task_T8 (AUD-17): {@code trySubscribe()}'s reconnect path used to leak the Lettuce
     * connection when {@code connectPubSub()} succeeded but the subsequent {@code subscribe()}
     * call threw - the already-opened connection was never closed, only ever retried again on
     * top of it. {@code start()} (the {@code @PostConstruct} entry point) is package-private and
     * called directly here rather than requiring a real Redis PubSub connection.
     */
    @Test
    void closesTheConnectionWhenSubscribeFailsAfterConnecting() {
        RedisClient redisClient = mock(RedisClient.class);
        @SuppressWarnings("unchecked")
        StatefulRedisPubSubConnection<String, String> connection = mock(StatefulRedisPubSubConnection.class);
        @SuppressWarnings("unchecked")
        RedisPubSubCommands<String, String> syncCommands = mock(RedisPubSubCommands.class);
        when(redisClient.connectPubSub()).thenReturn(connection);
        when(connection.sync()).thenReturn(syncCommands);
        doThrow(new RuntimeException("subscribe failed")).when(syncCommands).subscribe(anyString());
        ResponseCoordinator leaky = new ResponseCoordinator(redisClient, store, new ApiProperties());

        leaky.start();

        verify(connection).close();
        leaky.close();
    }
}
