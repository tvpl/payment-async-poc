package com.example.payments.sbus.outbox;

import com.example.payments.common.events.Topics;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.kafka.KafkaPublisher;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.ratelimit.RedisRateLimiter;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.support.Json;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves OutboxDispatcher marks each event PUBLISHED as soon as its own send succeeds — not
 * accumulated and marked only once the whole batch finishes — and that a failure acquiring the
 * distributed core-command rate limiter degrades to "defer this row" instead of aborting the
 * rest of the batch. Both were real gaps found by an independent review: a claim lease expiring
 * partway through a slow batch used to leave already-sent events unmarked (and therefore
 * reclaimable and republishable), and an unreachable Redis on the rate limiter used to escape
 * {@code dispatchBatch()} entirely, stopping every later row regardless of topic.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxBatchResilienceIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    private ApplicationContext context;
    private OutboxClaimService claims;
    private OutboxEventRepository repository;
    private OutboxReaper reaper;
    private OutboxPublicationLock publicationLock;
    private Json json;
    private Tracer tracer;

    @BeforeAll
    void start() {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
        REDIS.start();
        context = ApplicationContext.run(properties());
        claims = context.getBean(OutboxClaimService.class);
        repository = context.getBean(OutboxEventRepository.class);
        reaper = context.getBean(OutboxReaper.class);
        publicationLock = context.getBean(OutboxPublicationLock.class);
        json = context.getBean(Json.class);
        tracer = context.getBean(Tracer.class);
    }

    @BeforeEach
    void cleanDatabase() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM outbox_event");
        }
    }

    @AfterAll
    void stop() {
        context.close();
    }

    /**
     * task_T38 (SCAL-02): rewritten from the pre-parallel-dispatch version, which proved
     * "marked per item, not batched to the end" by observing strict row-to-row sequential
     * ordering (row 2's send asserting row 1 was already marked) — no longer meaningful once rows
     * dispatch concurrently, since nothing waits for anything else to start. The proof now holds
     * row 2 deliberately in flight (blocked) and asserts row 1's own mark is ALREADY visible while
     * the whole {@code dispatchBatch()} call has provably not returned yet — still exactly the
     * "as soon as its own send succeeds, not accumulated to the end" guarantee, via the mechanism
     * that fits concurrent dispatch.
     */
    @Test
    void dispatchBatchMarksEachEventPublishedAsSoonAsItsOwnSendSucceedsNotOnlyOnceTheWholeBatchReturns()
            throws Exception {
        repository.save(pendingEvent("mark-order-1", Topics.REQUESTED));
        repository.save(pendingEvent("mark-order-2", Topics.REQUESTED));

        CountDownLatch row2Blocked = new CountDownLatch(1);
        CountDownLatch releaseRow2 = new CountDownLatch(1);
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        org.mockito.Mockito.doAnswer(invocation -> null)
                .when(publisher).send(any(), eq("mark-order-1"), any(), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            row2Blocked.countDown();
            assertTrue(releaseRow2.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(publisher).send(any(), eq("mark-order-2"), any(), any());

        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<Integer> result =
                    executor.submit(dispatcher(publisher, mock(RedisRateLimiter.class))::dispatchBatch);
            assertTrue(row2Blocked.await(5, java.util.concurrent.TimeUnit.SECONDS));

            // The whole dispatchBatch() call has NOT returned yet (row 2 is still blocked in its
            // own send) — but row 1's own mark must already be visible.
            assertEquals("PUBLISHED", row("mark-order-1").status(),
                    "row 1 must already be marked PUBLISHED while row 2's send is still in "
                            + "flight, not only after the whole batch returns");
            assertEquals("IN_PROGRESS", row("mark-order-2").status());

            releaseRow2.countDown();
            assertEquals(2, result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals("PUBLISHED", row("mark-order-1").status());
        assertEquals("PUBLISHED", row("mark-order-2").status());
    }

    /**
     * task_T38 (SCAL-02): a batch of 10 with one artificially slow send must not take anywhere
     * close to 10x that row's own delay — proving the sends actually run in parallel, not
     * sequentially with a thin async wrapper around the same old loop.
     */
    @Test
    void aSlowSendInABatchOfTenDoesNotSerializeTheRestOfTheBatch() throws Exception {
        int batchSize = 10;
        for (int i = 0; i < batchSize; i++) {
            repository.save(pendingEvent("parallel-" + i, Topics.REQUESTED));
        }
        java.time.Duration slowSendDelay = java.time.Duration.ofMillis(800);
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            Thread.sleep(slowSendDelay.toMillis());
            return null;
        }).when(publisher).send(any(), eq("parallel-0"), any(), any());
        // The other nine rows use the default (instantaneous) mock answer.

        long startNanos = System.nanoTime();
        int published = dispatcher(publisher, mock(RedisRateLimiter.class)).dispatchBatch();
        java.time.Duration elapsed = java.time.Duration.ofNanos(System.nanoTime() - startNanos);

        assertEquals(batchSize, published);
        // Serialized, this would take >= 10 x 800ms = 8s; run in parallel it should finish close
        // to the one slow send's own duration — a generous 3x margin absorbs scheduling jitter
        // while still clearly distinguishing "parallel" from "serialized".
        assertTrue(elapsed.compareTo(slowSendDelay.multipliedBy(3)) < 0,
                "a batch of " + batchSize + " with one slow send took " + elapsed
                        + " — looks serialized, not parallel");
        for (int i = 0; i < batchSize; i++) {
            assertEquals("PUBLISHED", row("parallel-" + i).status());
        }
    }

    @Test
    void aLeaseExpiringMidBatchNeverRepublishesAnEventAlreadyMarkedPublished() throws Exception {
        // Three rows claimed together, as if a slow batch put all three IN_PROGRESS at once.
        repository.save(pendingEvent("batch-1a", Topics.REQUESTED));
        repository.save(pendingEvent("batch-1b", Topics.REQUESTED));
        repository.save(pendingEvent("batch-1c", Topics.REQUESTED));
        var claimed = claims.claimBatch();
        assertEquals(3, claimed.size());

        // Marks the first two exactly as dispatchBatch() itself would while working through the
        // claimed rows in order — the previous test proves that is really what it does; this one
        // focuses on the system-level effect once the lease then expires before the third send.
        assertTrue(claims.markPublished(claimed.get(0)));
        assertTrue(claims.markPublished(claimed.get(1)));

        // The lease now expires before the third row's send ever completes.
        ageClaim("batch-1a");
        ageClaim("batch-1b");
        ageClaim("batch-1c");
        reaper.reclaim();

        // The two already-published rows were never IN_PROGRESS when the reaper ran (marking
        // moved them out of that state immediately) — they must be untouched by the reclaim.
        assertEquals("PUBLISHED", row("batch-1a").status());
        assertEquals("PUBLISHED", row("batch-1b").status());
        // Only the row that genuinely never finished is reclaimed, back to PENDING for retry.
        assertEquals("PENDING", row("batch-1c").status());

        // Re-dispatching now must send the third row exactly once, and never touch the other two.
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        int published = dispatcher(publisher, limiter).dispatchBatch();

        assertEquals(1, published);
        verify(publisher, times(1)).send(eq(Topics.REQUESTED), eq("batch-1c"), any(), any());
        verify(publisher, never()).send(any(), eq("batch-1a"), any(), any());
        verify(publisher, never()).send(any(), eq("batch-1b"), any(), any());
        assertEquals("PUBLISHED", row("batch-1c").status());
    }

    /**
     * task_T31 (RES-06), rewritten for task_T38 (SCAL-02): the pre-parallel-dispatch version
     * asserted that specific ROWS (shutdown-2/shutdown-3) were NEVER even attempted once shutdown
     * fired mid-batch, relying on strict sequential ordering (shutdown fires during row 1's turn,
     * before rows 2/3 start). Under concurrent dispatch every eligible row's task starts at
     * roughly the same time, so which rows a real shutdown "catches" before their own send starts
     * is inherently racy — asserting a specific row was never attempted would be flaky by
     * construction, not a real regression signal. What still matters, and is what this asserts:
     * every row ends up in a SAFE terminal state (PUBLISHED, or cleanly PENDING with attempts
     * unchanged — never stuck IN_PROGRESS), and a dispatcher that has shut down never claims a
     * new batch afterward.
     */
    @Test
    void preDestroyStopsClaimingNewBatchesAndLeavesEveryRowSafeNeverStuckInProgress() throws Exception {
        repository.save(pendingEvent("shutdown-1", Topics.REQUESTED));
        repository.save(pendingEvent("shutdown-2", Topics.REQUESTED));
        repository.save(pendingEvent("shutdown-3", Topics.REQUESTED));

        KafkaPublisher publisher = mock(KafkaPublisher.class);
        OutboxDispatcher dispatcher = dispatcher(publisher, mock(RedisRateLimiter.class));
        CountDownLatch aRowStartedSending = new CountDownLatch(1);
        CountDownLatch releaseSends = new CountDownLatch(1);
        // Every row's send blocks until released — standing in for "still in flight" when the
        // real @PreDestroy hook fires on another thread while a batch is being dispatched.
        org.mockito.Mockito.doAnswer(invocation -> {
            aRowStartedSending.countDown();
            assertTrue(releaseSends.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(publisher).send(any(), any(), any(), any());

        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<Integer> batchResult = executor.submit(dispatcher::dispatchBatch);
            assertTrue(aRowStartedSending.await(5, java.util.concurrent.TimeUnit.SECONDS));

            dispatcher.shutdown();
            releaseSends.countDown();

            int published = batchResult.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(published >= 0 && published <= 3);
        }

        for (String identity : List.of("shutdown-1", "shutdown-2", "shutdown-3")) {
            Row current = row(identity);
            assertTrue("PUBLISHED".equals(current.status()) || "PENDING".equals(current.status()),
                    identity + " must end up PUBLISHED or cleanly PENDING, never stuck: was "
                            + current.status());
            if ("PENDING".equals(current.status())) {
                assertEquals(0, attemptsOf(identity),
                        "a clean shutdown release must not increment attempts the way a reaper reclaim would");
            }
        }

        // A dispatcher that has shut down must not claim a new batch on a later poll.
        repository.save(pendingEvent("shutdown-4", Topics.REQUESTED));
        int publishedAfterShutdown = dispatcher.dispatchBatch();
        assertEquals(0, publishedAfterShutdown, "a dispatcher that has shut down must not claim a new batch");
        assertEquals("PENDING", row("shutdown-4").status());
        verify(publisher, never()).send(any(), eq("shutdown-4"), any(), any());
    }

    private static int attemptsOf(String identity) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement(
                     "SELECT attempts FROM outbox_event WHERE deduplication_key = ?")) {
            statement.setString(1, identity);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void aRateLimiterFailureDefersOnlyTheThrottledRowAndTheRestOfTheBatchStillPublishes() throws Exception {
        // A mixed batch: two rows on topics the rate limiter never governs, one core.command row.
        repository.save(pendingEvent("batch-2-completed", Topics.COMPLETED));
        repository.save(pendingEvent("batch-2-failed", Topics.FAILED));
        repository.save(pendingEvent("batch-2-command", Topics.CORE_COMMAND));

        KafkaPublisher publisher = mock(KafkaPublisher.class);
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.tryAcquire()).thenThrow(new RuntimeException("Redis unavailable"));

        int published = assertDoesNotThrow(() -> dispatcher(publisher, limiter).dispatchBatch(),
                "a Redis outage on the rate limiter must not escape dispatchBatch()");

        // Both unthrottled rows went through; the throttled one did not, and was not lost.
        assertEquals(2, published);
        assertEquals("PUBLISHED", row("batch-2-completed").status());
        assertEquals("PUBLISHED", row("batch-2-failed").status());
        assertEquals("PENDING", row("batch-2-command").status());
        verify(publisher, times(1)).send(eq(Topics.COMPLETED), eq("batch-2-completed"), any(), any());
        verify(publisher, times(1)).send(eq(Topics.FAILED), eq("batch-2-failed"), any(), any());
        verify(publisher, never()).send(any(), eq("batch-2-command"), any(), any());
    }

    /**
     * task_T12 (AUD-07), rewritten for task_T38 (SCAL-02): each row now renews (and re-verifies)
     * its OWN claim immediately before its OWN send (see {@code OutboxDispatcher#dispatchOne}),
     * instead of the dispatcher renewing "the rows still waiting their turn" after each
     * sequential row's own turn. Slow-2's claim freshness no longer depends on slow-1's own send
     * completing first — it is renewed on slow-2's own independent thread, so a slow SIBLING row
     * never delays it.
     */
    @Test
    void aSlowSiblingRowNeverDelaysAnotherRowsOwnLeaseRenewal() throws Exception {
        repository.save(pendingEvent("slow-1", Topics.REQUESTED));
        repository.save(pendingEvent("slow-2", Topics.REQUESTED));

        CountDownLatch slow1Started = new CountDownLatch(1);
        CountDownLatch releaseSlow1 = new CountDownLatch(1);
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            slow1Started.countDown();
            assertTrue(releaseSlow1.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(publisher).send(any(), eq("slow-1"), any(), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            // slow-2's own claim must already be freshly renewed by the time ITS OWN send
            // happens, regardless of slow-1 still being blocked above — its renewal never had to
            // wait for an unrelated sibling's still-in-flight send.
            assertTrue(claimedAtIsRecent("slow-2"),
                    "slow-2's claim must already have been renewed before its own send, "
                            + "independent of slow-1's own pace");
            return null;
        }).when(publisher).send(any(), eq("slow-2"), any(), any());

        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<Integer> result =
                    executor.submit(dispatcher(publisher, mock(RedisRateLimiter.class))::dispatchBatch);
            assertTrue(slow1Started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            releaseSlow1.countDown();
            assertEquals(2, result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals("PUBLISHED", row("slow-1").status());
        assertEquals("PUBLISHED", row("slow-2").status());
        verify(publisher, times(1)).send(any(), eq("slow-1"), any(), any());
        verify(publisher, times(1)).send(any(), eq("slow-2"), any(), any());
    }

    /**
     * task_T12 (AUD-07), rewritten for task_T38 (SCAL-02): a row's claim going stale (reclaimed by
     * something else — a concurrent reaper cycle) WHILE its own send is already in flight must
     * never let it falsely count as PUBLISHED — {@code markPublished}'s own fencing (by claim
     * token) catches it after the send returns. The row stays safely recoverable (PENDING, already
     * reclaimed once) instead of being double-marked. Deterministic by construction: both sends
     * are held open on a latch (proving both renewals already succeeded) before the reclaim is
     * triggered explicitly from the test thread — no cross-thread race to win.
     */
    @Test
    void aRowReclaimedWhileItsSendIsInFlightNeverFalselyCountsAsPublished() throws Exception {
        repository.save(pendingEvent("fence-1", Topics.REQUESTED));
        repository.save(pendingEvent("fence-2", Topics.REQUESTED));

        CountDownLatch bothSendsStarted = new CountDownLatch(2);
        CountDownLatch releaseSends = new CountDownLatch(1);
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            bothSendsStarted.countDown();
            assertTrue(releaseSends.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(publisher).send(any(), any(), any(), any());

        OutboxDispatcher dispatcher = dispatcher(publisher, mock(RedisRateLimiter.class));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<Integer> batchResult = executor.submit(dispatcher::dispatchBatch);
            assertTrue(bothSendsStarted.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "both rows' own renewal must already have succeeded and their sends started");

            // Reclaim fence-1's claim while its send is still in flight — the exact window a slow
            // sibling row can leave open under SCAL-02's parallel dispatch.
            ageClaim("fence-1");
            reaper.reclaim();
            assertEquals("PENDING", row("fence-1").status(), "reclaimed while fence-1's send is in flight");

            releaseSends.countDown();
            int published = batchResult.get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(1, published,
                    "only fence-2 counts — fence-1's claim went stale before it could mark published");
        }
        // fence-1 WAS sent to Kafka (its send ran to completion) but never falsely marked
        // published, since markPublished's own fencing rejected the now-stale claim.
        assertEquals("PENDING", row("fence-1").status());
        assertEquals("PUBLISHED", row("fence-2").status());
        verify(publisher, times(1)).send(any(), eq("fence-1"), any(), any());
        verify(publisher, times(1)).send(any(), eq("fence-2"), any(), any());
    }

    private boolean claimedAtIsRecent(String identity) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT claimed_at > now() - interval '5 seconds' FROM outbox_event
                     WHERE deduplication_key = ?
                     """)) {
            statement.setString(1, identity);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private OutboxDispatcher dispatcher(KafkaPublisher publisher, RedisRateLimiter limiter) {
        return new OutboxDispatcher(claims, publisher, mock(SbusMetrics.class), json, limiter, publicationLock, tracer);
    }

    private static OutboxEvent pendingEvent(String identity, String topic) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("test");
        event.setAggregateId(identity);
        event.setEventType("test");
        event.setTopic(topic);
        event.setKey(identity);
        event.setPayload(new byte[]{1});
        event.setHeaders("{}");
        event.setStatus(OutboxStatus.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(Instant.now().minusSeconds(1));
        event.setDeduplicationKey(identity);
        return event;
    }

    private record Row(String status, String topic) {
    }

    private static Row row(String identity) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement(
                     "SELECT status, topic FROM outbox_event WHERE deduplication_key = ?")) {
            statement.setString(1, identity);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return new Row(result.getString(1), result.getString(2));
            }
        }
    }

    private static void ageClaim(String identity) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     UPDATE outbox_event SET claimed_at = now() - interval '5 seconds'
                     WHERE deduplication_key = ?
                     """)) {
            statement.setString(1, identity);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }

    private static Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("apicurio.registry.url", registryUrl()),
                Map.entry("redis.uri", REDIS.getRedisURI()),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl() + "?stringtype=unspecified"),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("sbus.outbox.batch-size", 10),
                Map.entry("sbus.outbox.max-attempts", 2),
                Map.entry("sbus.outbox.base-backoff", "10ms"),
                Map.entry("sbus.outbox.max-backoff", "10ms"),
                // Every reclaim in this class is driven by ageClaim()'s explicit "now() - 5
                // seconds" (never by the lease alone), so this only needs to comfortably
                // separate "genuinely aged 5s ago" from "just renewed moments ago" — 1ms gave
                // renewClaim's fresh timestamp no real margin against ordinary JVM/DB scheduling
                // jitter, so a row renewed right before the reaper ran (see
                // aRowReclaimedWhileItsSendIsInFlightNeverFalselyCountsAsPublished) could look
                // just as stale as the row deliberately aged, and get wrongly reclaimed too.
                Map.entry("sbus.outbox.lease", "1s"),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
