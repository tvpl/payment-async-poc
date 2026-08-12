package com.example.payments.sbus.retry;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.domain.OutboxEvent;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.kafka.KafkaPublisher;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.outbox.OutboxClaimService;
import com.example.payments.sbus.outbox.OutboxDispatcher;
import com.example.payments.sbus.outbox.OutboxPublicationLock;
import com.example.payments.sbus.outbox.OutboxReaper;
import com.example.payments.sbus.ratelimit.RedisRateLimiter;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.support.Json;
import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecoverableDeadLetterIT {

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
    private DurableDeadLetterScheduler scheduler;
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
        scheduler = context.getBean(DurableDeadLetterScheduler.class);
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
    void poisonRecordIsDurableBeforeConsumerCanCommit() throws Exception {
        byte[] raw = new byte[]{9, 8, 7};
        var result = scheduler.schedule(Topics.REQUESTED, record(7, raw),
                Map.of(Headers.TRACEPARENT, "trace-7"), new RuntimeException("invalid"), "poison");

        assertTrue(result.inserted());
        Row row = row(result.deduplicationKey());
        assertEquals("DLQ_PENDING", row.status());
        assertEquals(Topics.DLQ, row.topic());
        assertArrayEquals(raw, row.payload());
        Map<?, ?> headers = json.fromJson(row.headers(), Map.class);
        assertEquals("trace-7", headers.get(Headers.TRACEPARENT));
        assertEquals("poison", headers.get("x-dlq-stage"));
    }

    @Test
    void brokerFailureLeavesDeadLetterPendingAndAlertable() throws Exception {
        var result = scheduler.schedule(Topics.REQUESTED, record(8, new byte[]{8}),
                Map.of(), new RuntimeException("invalid"), "poison");
        KafkaPublisher publisher = mock(KafkaPublisher.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher).send(any(), any(), any(), any());

        Instant beforeFailure = Instant.now();
        assertEquals(0, dispatcher(publisher).dispatchBatch());

        Row row = row(result.deduplicationKey());
        assertEquals("DLQ_PENDING", row.status());
        assertEquals(1, row.attempts());
        assertTrue(row.lastError().contains("broker unavailable"));
        assertTrue(row.nextAttemptAt().isAfter(beforeFailure));
        assertEquals(1, repository.countByStatus(OutboxStatus.DLQ_PENDING));
    }

    @Test
    void brokerAcknowledgementIsTheOnlyTerminalBoundary() throws Exception {
        byte[] raw = new byte[]{6, 2};
        var result = scheduler.schedule(Topics.REQUESTED, record(9, raw), Map.of(), null, "poison");
        KafkaPublisher publisher = mock(KafkaPublisher.class);

        assertEquals(1, dispatcher(publisher).dispatchBatch());

        Row row = row(result.deduplicationKey());
        assertEquals("DLQ_PUBLISHED", row.status());
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(publisher).send(org.mockito.ArgumentMatchers.eq(Topics.DLQ),
                org.mockito.ArgumentMatchers.eq("request-9"), bytes.capture(), any());
        assertArrayEquals(raw, bytes.getValue());
    }

    @Test
    void crashAfterBrokerAckReclaimsAndRepublishesSameIdentity() throws Exception {
        byte[] raw = new byte[]{5, 5};
        Map<String, String> sourceHeaders = Map.of(
                Headers.TRACEPARENT, "00-crash-trace",
                Headers.CORRELATION_ID, "correlation-10",
                Headers.CAUSATION_ID, "causation-10");
        var result = scheduler.schedule(Topics.REQUESTED, record(10, raw), sourceHeaders, null, "poison");
        OutboxEvent claimed = claims.claimBatch().getFirst();
        KafkaPublisher publisher = mock(KafkaPublisher.class);

        publisher.send(claimed.getTopic(), claimed.getKey(), claimed.getPayload(),
                json.fromJson(claimed.getHeaders(), Map.class));
        ageClaim(result.deduplicationKey());
        reaper.reclaim();
        assertEquals(1, dispatcher(publisher).dispatchBatch());

        assertEquals("DLQ_PUBLISHED", row(result.deduplicationKey()).status());
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(publisher, times(2)).send(org.mockito.ArgumentMatchers.eq(Topics.DLQ),
                org.mockito.ArgumentMatchers.eq("request-10"), payloads.capture(), headers.capture());
        assertEquals(2, payloads.getAllValues().size());
        assertArrayEquals(raw, payloads.getAllValues().get(0));
        assertArrayEquals(raw, payloads.getAllValues().get(1));
        for (Map<String, String> sentHeaders : headers.getAllValues()) {
            assertEquals("00-crash-trace", sentHeaders.get(Headers.TRACEPARENT));
            assertEquals("correlation-10", sentHeaders.get(Headers.CORRELATION_ID));
            assertEquals("causation-10", sentHeaders.get(Headers.CAUSATION_ID));
            assertEquals("poison", sentHeaders.get("x-dlq-stage"));
        }
    }

    @Test
    void twoInstancesCannotClaimTheSameDeadLetter() throws Exception {
        scheduler.schedule(Topics.REQUESTED, record(11, new byte[]{1, 1}), Map.of(), null, "poison");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<OutboxEvent>> first = executor.submit(() -> {
                start.await();
                return claims.claimBatch();
            });
            Future<List<OutboxEvent>> second = executor.submit(() -> {
                start.await();
                return claims.claimBatch();
            });
            start.countDown();

            assertEquals(1, first.get().size() + second.get().size());
        }
    }

    @Test
    void expiredLeaseFencesLateSuccessAndFailureFromPreviousOwner() throws Exception {
        var scheduled = scheduler.schedule(Topics.REQUESTED, record(12, new byte[]{1, 2}),
                Map.of(), null, "poison");
        OutboxEvent ownerA = claims.claimBatch().getFirst();
        ageClaim(scheduled.deduplicationKey());
        reaper.reclaim();
        OutboxEvent ownerB = claims.claimBatch().getFirst();

        assertNotEquals(ownerA.getClaimToken(), ownerB.getClaimToken());
        assertFalse(claims.markPublished(ownerA));
        assertEquals(OutboxClaimService.FailureDisposition.STALE_CLAIM,
                claims.markFailure(ownerA, "late failure", "{}"));
        Row ownedByB = row(scheduled.deduplicationKey());
        assertEquals("IN_PROGRESS", ownedByB.status());
        assertEquals(ownerB.getClaimToken(), ownedByB.claimToken());

        assertTrue(claims.markPublished(ownerB));
        assertEquals("DLQ_PUBLISHED", row(scheduled.deduplicationKey()).status());
    }

    @Test
    void publicationLockPreventsSendOverlapAfterLeaseReclaim() throws Exception {
        var scheduled = scheduler.schedule(Topics.REQUESTED, record(13, new byte[]{1, 3}),
                Map.of(), null, "poison");
        CountDownLatch ownerASending = new CountDownLatch(1);
        CountDownLatch releaseOwnerA = new CountDownLatch(1);
        KafkaPublisher ownerAPublisher = mock(KafkaPublisher.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            ownerASending.countDown();
            assertTrue(releaseOwnerA.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(ownerAPublisher).send(any(), any(), any(), any());
        KafkaPublisher ownerBPublisher = mock(KafkaPublisher.class);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> ownerA = executor.submit(() -> dispatcher(ownerAPublisher).dispatchBatch());
            assertTrue(ownerASending.await(5, java.util.concurrent.TimeUnit.SECONDS));
            ageClaim(scheduled.deduplicationKey());
            reaper.reclaim();

            assertEquals(0, dispatcher(ownerBPublisher).dispatchBatch());
            verifyNoInteractions(ownerBPublisher);

            releaseOwnerA.countDown();
            assertEquals(0, ownerA.get());
        }

        assertEquals("DLQ_PENDING", row(scheduled.deduplicationKey()).status());
        makeDue(scheduled.deduplicationKey());
        assertEquals(1, dispatcher(ownerBPublisher).dispatchBatch());
        verify(ownerBPublisher).send(org.mockito.ArgumentMatchers.eq(Topics.DLQ),
                org.mockito.ArgumentMatchers.eq("request-13"), any(), any());
        assertEquals("DLQ_PUBLISHED", row(scheduled.deduplicationKey()).status());
    }

    @Test
    void unconfirmedMetricsRemainContinuousWhileDeadLetterIsClaimed() throws Exception {
        var scheduled = scheduler.schedule(Topics.REQUESTED, record(14, new byte[]{1, 4}),
                Map.of(), null, "poison");
        Row pending = row(scheduled.deduplicationKey());
        long pendingAge = repository.oldestUnconfirmedDeadLetterAgeSeconds();
        assertEquals(1, repository.countUnconfirmedDeadLetters());

        OutboxEvent claimed = claims.claimBatch().getFirst();
        Row inProgress = row(scheduled.deduplicationKey());

        assertEquals("IN_PROGRESS", inProgress.status());
        assertEquals(pending.dlqStartedAt(), inProgress.dlqStartedAt());
        assertEquals(1, repository.countUnconfirmedDeadLetters());
        assertTrue(repository.oldestUnconfirmedDeadLetterAgeSeconds() >= pendingAge);
        claims.release(claimed, Instant.now().plusSeconds(1));
    }

    @Test
    void exhaustedNormalOutboxBecomesRecoverableDlqWorkNeverFailed() throws Exception {
        OutboxEvent event = pendingEvent("normal-exhaustion", Topics.CORE_COMMAND);
        repository.save(event);

        OutboxEvent firstClaim = claims.claimBatch().getFirst();
        assertEquals(OutboxClaimService.FailureDisposition.RETRY_PENDING,
                claims.markFailure(firstClaim, "first", "{}"));
        makeDue("normal-exhaustion");
        OutboxEvent secondClaim = claims.claimBatch().getFirst();
        assertEquals(OutboxClaimService.FailureDisposition.DLQ_PENDING,
                claims.markFailure(secondClaim, "second", "{\"x-dlq-stage\":\"outbox-publish\"}"));

        Row row = row("normal-exhaustion");
        assertEquals("DLQ_PENDING", row.status());
        assertEquals(Topics.DLQ, row.topic());
        assertFalse("FAILED".equals(row.status()));
    }

    private OutboxDispatcher dispatcher(KafkaPublisher publisher) {
        return new OutboxDispatcher(claims, publisher, mock(SbusMetrics.class), json,
                mock(RedisRateLimiter.class), publicationLock);
    }

    private static ConsumerRecord<String, byte[]> record(long offset, byte[] value) {
        return new ConsumerRecord<>(Topics.REQUESTED, 0, offset, "request-" + offset, value);
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

    private static Row row(String identity) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT status, topic, payload, headers::text, attempts, COALESCE(last_error, ''),
                            next_attempt_at, claim_token, dlq_started_at
                     FROM outbox_event WHERE deduplication_key = ?
                     """)) {
            statement.setString(1, identity);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return new Row(result.getString(1), result.getString(2), result.getBytes(3),
                        result.getString(4), result.getInt(5), result.getString(6),
                        result.getTimestamp(7).toInstant(), result.getObject(8, UUID.class),
                        result.getTimestamp(9) == null ? null : result.getTimestamp(9).toInstant());
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

    private static void makeDue(String identity) throws Exception {
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     UPDATE outbox_event SET next_attempt_at = now() - interval '1 second'
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

    private static Map<String, Object> properties() {
        return Map.ofEntries(
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("apicurio.registry.url", registryUrl()),
                Map.entry("redis.uri", REDIS.getRedisURI()),
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl() + "?stringtype=unspecified"),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("sbus.outbox.batch-size", 1),
                Map.entry("sbus.outbox.max-attempts", 2),
                Map.entry("sbus.outbox.base-backoff", "10ms"),
                Map.entry("sbus.outbox.max-backoff", "10ms"),
                Map.entry("sbus.outbox.lease", "1ms"),
                Map.entry("sbus.outbox.initial-delay", "1h"),
                Map.entry("sbus.outbox.poll-interval", "1h"),
                Map.entry("otel.traces.exporter", "none"));
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080)
                + "/apis/registry/v2";
    }

    private record Row(String status, String topic, byte[] payload, String headers,
                       int attempts, String lastError, Instant nextAttemptAt, UUID claimToken,
                       Instant dlqStartedAt) {
    }
}
