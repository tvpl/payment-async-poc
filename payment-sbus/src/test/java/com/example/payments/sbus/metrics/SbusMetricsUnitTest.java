package com.example.payments.sbus.metrics;

import com.example.payments.sbus.domain.OutboxStatus;
import com.example.payments.sbus.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SbusMetricsUnitTest {

    @Test
    void exposesRecoverableDeadLetterBacklogForAlerting() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.countUnconfirmedDeadLetters()).thenReturn(7L);
        when(repository.oldestUnconfirmedDeadLetterAgeSeconds()).thenReturn(901L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SbusMetrics metrics = new SbusMetrics(registry, repository);

        metrics.init();

        assertEquals(7.0, registry.get("sbus_dlq_unconfirmed").gauge().value());
        assertEquals(901.0,
                registry.get("sbus_dlq_unconfirmed_oldest_age_seconds").gauge().value());
    }
}
