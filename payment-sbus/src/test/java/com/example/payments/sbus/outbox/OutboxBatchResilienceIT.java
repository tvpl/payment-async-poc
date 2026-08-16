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

    @Test
    void dispatchBatchMarksEachEventPublishedAsSoonAsItsOwnSendSucceedsNotAtTheEndOfTheBatch()
            throws Exception {
        // Two rows claimed as one batch. If marking happened only once at the end of the whole
        // loop (the old behavior), the first row would still be IN_PROGRESS while the second is
        // being sent. Asserting from inside the second send's own answer observes exactly that
        // instant — this is the check that would fail against the pre-fix code.
        repository.save(pendingEvent("mark-order-1", Topics.REQUESTED));
        repository.save(pendingEvent("mark-order-2", Topics.REQUESTED));

        KafkaPublisher publisher = mock(KafkaPublisher.class);
        org.mockito.Mockito.doAnswer(invocation -> null)
                .when(publisher).send(any(), eq("mark-order-1"), any(), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            assertEquals("PUBLISHED", row("mark-order-1").status(),
                    "the first event must already be marked PUBLISHED by the time the second "
                            + "one's send is attempted, not only after the whole batch returns");
            return null;
        }).when(publisher).send(any(), eq("mark-order-2"), any(), any());

        int published = dispatcher(publisher, mock(RedisRateLimiter.class)).dispatchBatch();

        assertEquals(2, published);
        assertEquals("PUBLISHED", row("mark-order-1").status());
        assertEquals("PUBLISHED", row("mark-order-2").status());
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
     * task_T12 (AUD-07): each row published used to leave the REMAINING claimed rows' lease
     * untouched — a slow batch (one row taking a long time) could let the claim lease of rows
     * still waiting their turn expire mid-batch, so the reaper could reclaim (and a later poll
     * could republish) a row this exact dispatcher instance was still actively about to send.
     * Row 1's mocked send backdates rows 2 and 3's {@code claimed_at} directly — standing in for
     * "a lot of wall-clock time passed while row 1 was slow" without a flaky real sleep — and each
     * later row's own send asserts its claim was already renewed (fresh, not the backdated value)
     * BEFORE its own turn began, proving the dispatcher renews proactively as it goes, not by luck.
     */
    @Test
    void aSlowBatchRenewsTheRemainingClaimedRowsSoNoneIsEverLeftToOutliveItsLease() throws Exception {
        repository.save(pendingEvent("slow-1", Topics.REQUESTED));
        repository.save(pendingEvent("slow-2", Topics.REQUESTED));
        repository.save(pendingEvent("slow-3", Topics.REQUESTED));

        KafkaPublisher publisher = mock(KafkaPublisher.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            backdateClaim("slow-2");
            backdateClaim("slow-3");
            return null;
        }).when(publisher).send(any(), eq("slow-1"), any(), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            assertEquals("IN_PROGRESS", row("slow-2").status());
            assertTrue(claimedAtIsRecent("slow-2"),
                    "slow-2's claim must already have been renewed before its own turn began");
            return null;
        }).when(publisher).send(any(), eq("slow-2"), any(), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            assertTrue(claimedAtIsRecent("slow-3"),
                    "slow-3's claim must already have been renewed before slow-2's turn began");
            return null;
        }).when(publisher).send(any(), eq("slow-3"), any(), any());

        int published = dispatcher(publisher, mock(RedisRateLimiter.class)).dispatchBatch();

        assertEquals(3, published);
        assertEquals("PUBLISHED", row("slow-1").status());
        assertEquals("PUBLISHED", row("slow-2").status());
        assertEquals("PUBLISHED", row("slow-3").status());
        verify(publisher, times(1)).send(any(), eq("slow-1"), any(), any());
        verify(publisher, times(1)).send(any(), eq("slow-2"), any(), any());
        verify(publisher, times(1)).send(any(), eq("slow-3"), any(), any());
    }

    /**
     * task_T12 (AUD-07): a genuinely lost fence — something else already reclaimed a remaining
     * row while this batch was mid-flight — must still abort the rest of the batch and record a
     * metric, exactly as any other outbox publish failure does (existing behavior preserved).
     * {@code forceReclaim} stands in for a real concurrent reaper cycle winning the race against
     * fence-2 before this dispatcher's own renewal gets to it.
     */
    @Test
    void aTrulyLostFenceDuringRenewalAbortsTheRestOfTheBatchWithAMetric() throws Exception {
        repository.save(pendingEvent("fence-1", Topics.REQUESTED));
        repository.save(pendingEvent("fence-2", Topics.REQUESTED));
        repository.save(pendingEvent("fence-3", Topics.REQUESTED));

        KafkaPublisher publisher = mock(KafkaPublisher.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            forceReclaim("fence-2");
            return null;
        }).when(publisher).send(any(), eq("fence-1"), any(), any());

        SbusMetrics metrics = mock(SbusMetrics.class);
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                claims, publisher, metrics, json, mock(RedisRateLimiter.class), publicationLock);
        int published = dispatcher.dispatchBatch();

        assertEquals(1, published, "only fence-1, already sent before the fence was lost, counts as published");
        assertEquals("PUBLISHED", row("fence-1").status());
        // fence-2 was reclaimed out from under the batch (simulating a real concurrent reaper) —
        // the aborted batch must not touch it further.
        assertEquals("PENDING", row("fence-2").status());
        // fence-3 was never reached: the batch aborted instead of ploughing ahead once its own
        // fencing of the remaining rows could no longer be trusted. Its own claim is untouched —
        // the reaper's normal schedule recovers it once that claim's own lease expires.
        assertEquals("IN_PROGRESS", row("fence-3").status());
        verify(publisher, never()).send(any(), eq("fence-2"), any(), any());
        verify(publisher, never()).send(any(), eq("fence-3"), any(), any());
        verify(metrics).recordPublishFailure();
    }

    private void backdateClaim(String identity) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     UPDATE outbox_event SET claimed_at = now() - interval '10 seconds'
                     WHERE deduplication_key = ?
                     """)) {
            statement.setString(1, identity);
            assertEquals(1, statement.executeUpdate());
        }
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

    private void forceReclaim(String identity) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     UPDATE outbox_event SET status = 'PENDING', claimed_at = NULL, claim_token = NULL
                     WHERE deduplication_key = ?
                     """)) {
            statement.setString(1, identity);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private OutboxDispatcher dispatcher(KafkaPublisher publisher, RedisRateLimiter limiter) {
        return new OutboxDispatcher(claims, publisher, mock(SbusMetrics.class), json, limiter, publicationLock);
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
                Map.entry("sbus.outbox.lease", "1ms"),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }
}
