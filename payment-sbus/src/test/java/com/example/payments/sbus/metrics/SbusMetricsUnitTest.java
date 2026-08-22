package com.example.payments.sbus.metrics;

import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SbusMetricsUnitTest {

    @Test
    void exposesRecoverableDeadLetterBacklogForAlerting() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.countUnconfirmedDeadLetters()).thenReturn(7L);
        when(repository.oldestUnconfirmedDeadLetterAgeSeconds()).thenReturn(901L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SbusMetrics metrics = new SbusMetrics(registry, repository, mock(AvroSerde.class));

        metrics.init();

        assertEquals(7.0, registry.get("sbus_dlq_unconfirmed").gauge().value());
        assertEquals(901.0,
                registry.get("sbus_dlq_unconfirmed_oldest_age_seconds").gauge().value());
    }

    /**
     * task_T35 (OBS-04): the gauges must read a cached value, never run a fresh {@code COUNT(*)}
     * on every scrape. {@code init()} populates the cache once (its own, single, startup query);
     * any number of subsequent gauge reads within the TTL must not add further repository calls.
     */
    @Test
    void consecutiveScrapesWithinTheTtlNeverTriggerMoreThanTheOneInitialQuery() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.countByStatus(OutboxStatus.PENDING)).thenReturn(5L);
        when(repository.countUnconfirmedDeadLetters()).thenReturn(2L);
        when(repository.oldestUnconfirmedDeadLetterAgeSeconds()).thenReturn(30L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SbusMetrics metrics = new SbusMetrics(registry, repository, mock(AvroSerde.class));

        metrics.init();

        double first = registry.get("sbus_outbox_pending").gauge().value();
        double second = registry.get("sbus_outbox_pending").gauge().value();
        double third = registry.get("sbus_outbox_pending").gauge().value();

        assertEquals(5.0, first);
        assertEquals(5.0, second);
        assertEquals(5.0, third);
        verify(repository, times(1)).countByStatus(OutboxStatus.PENDING);
        verify(repository, times(1)).countUnconfirmedDeadLetters();
        verify(repository, times(1)).oldestUnconfirmedDeadLetterAgeSeconds();
    }

    /**
     * task_T35 (OBS-04): proves the cache actually refreshes (it is not just a permanently frozen
     * value) — triggering the same method the fixed 15s schedule calls updates what subsequent
     * scrapes see, at the cost of exactly one more query per refresh, not one per scrape.
     */
    @Test
    void refreshCachedCountsUpdatesTheValueSubsequentScrapesSee() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.countByStatus(OutboxStatus.PENDING)).thenReturn(5L, 12L);
        when(repository.countUnconfirmedDeadLetters()).thenReturn(0L);
        when(repository.oldestUnconfirmedDeadLetterAgeSeconds()).thenReturn(0L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SbusMetrics metrics = new SbusMetrics(registry, repository, mock(AvroSerde.class));
        metrics.init();
        assertEquals(5.0, registry.get("sbus_outbox_pending").gauge().value());

        metrics.refreshCachedCounts();

        assertEquals(12.0, registry.get("sbus_outbox_pending").gauge().value());
        verify(repository, times(2)).countByStatus(OutboxStatus.PENDING);
    }

    /**
     * A {@code @PostConstruct} failure aborts the whole application context — Postgres being
     * down at boot must never crash-loop the SBUS instead of starting degraded. {@code init()}'s
     * own startup population is best-effort: it must not propagate the repository's exception,
     * and once the database recovers the next scheduled {@link SbusMetrics#refreshCachedCounts()}
     * must still populate the gauges normally.
     */
    @Test
    void initNeverPropagatesARepositoryFailureSoAPostgresOutageAtBootNeverAbortsTheContext() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.countByStatus(OutboxStatus.PENDING))
                .thenThrow(new RuntimeException("Postgres unreachable"))
                .thenReturn(9L);
        when(repository.countUnconfirmedDeadLetters()).thenThrow(new RuntimeException("Postgres unreachable"));
        when(repository.oldestUnconfirmedDeadLetterAgeSeconds())
                .thenThrow(new RuntimeException("Postgres unreachable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SbusMetrics metrics = new SbusMetrics(registry, repository, mock(AvroSerde.class));

        metrics.init();

        assertEquals(0.0, registry.get("sbus_outbox_pending").gauge().value());

        // Re-stubbing via when(mock.method()) would invoke the still-throwing previous stub
        // before it could be replaced — doReturn(...).when(mock) sidesteps that.
        doReturn(0L).when(repository).countUnconfirmedDeadLetters();
        doReturn(0L).when(repository).oldestUnconfirmedDeadLetterAgeSeconds();
        metrics.refreshCachedCounts();

        assertEquals(9.0, registry.get("sbus_outbox_pending").gauge().value());
    }

    /** task_T35 (OBS-05): the Avro codec pool gauges must be visible in the test registry. */
    @Test
    void exposesTheAvroCodecPoolSnapshotAsGauges() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        AvroSerde avroSerde = mock(AvroSerde.class);
        when(avroSerde.poolSnapshot()).thenReturn(new AvroSerde.PoolSnapshot(8, 5, 3, 2));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SbusMetrics metrics = new SbusMetrics(registry, repository, avroSerde);

        metrics.init();

        assertEquals(8.0, registry.get("sbus_avro_codec_pool_capacity").gauge().value());
        assertEquals(5.0, registry.get("sbus_avro_codec_pool_available").gauge().value());
        assertEquals(3.0, registry.get("sbus_avro_codec_pool_borrowed").gauge().value());
        assertEquals(2.0, registry.get("sbus_avro_codec_pool_timeouts_total").gauge().value());
    }
}
