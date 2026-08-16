package com.example.payments.api.kafka;

import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.error.StoreUnavailableException;
import com.example.payments.api.metrics.ApiMetrics;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.avro.PaymentSimulationCompleted;
import com.example.payments.common.avro.SimulationResultPayload;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroCodecUnavailableException;
import com.example.payments.common.kafka.AvroSerde;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Classification of failures on the response path: content we can never read is dead-lettered,
 * capacity we momentarily lack is redelivered, and a DLQ that cannot confirm is never
 * treated as done (PAY-09).
 */
class PaymentResponseConsumerUnitTest {

    private AvroSerde avroSerde;
    private ResponseDeadLetters deadLetters;
    private RedisStatusStore store;
    private PaymentResponseConsumer consumer;

    @BeforeEach
    void setUp() {
        avroSerde = mock(AvroSerde.class);
        deadLetters = mock(ResponseDeadLetters.class);
        store = mock(RedisStatusStore.class);
        ResponseConsumerProperties properties = new ResponseConsumerProperties();
        properties.setMaxAttempts(2);
        properties.setRetryDelay(Duration.ZERO);
        consumer = new PaymentResponseConsumer(
                store, mock(ResponseCoordinator.class), avroSerde,
                mock(ApiMetrics.class), deadLetters, properties);
    }

    private static ConsumerRecord<String, byte[]> record() {
        return new ConsumerRecord<>(Topics.COMPLETED, 0, 7L, "req-1", new byte[]{9, 9});
    }

    private static PaymentSimulationCompleted completedAvroEvent() {
        SimulationResultPayload payload = new SimulationResultPayload(
                "sim-1", "req-1", "APPROVED", "123456", "125.50", "BRL", 1, null, null, null, null);
        return new PaymentSimulationCompleted(
                "event-1", "PaymentSimulationCompleted", "1", 0L, "req-1", "corr-1", null, null, "sbus", "", payload);
    }

    @Test
    void anUnreadableRecordIsDeadLetteredAtTheDecodeStage() {
        when(avroSerde.deserialize(anyString(), any())).thenThrow(new IllegalStateException("bad magic byte"));

        consumer.receive(record());

        verify(deadLetters).route(any(), eq(PaymentResponseConsumer.STAGE_DECODE), any());
    }

    @Test
    void aCodecCapacityShortageIsRedeliveredInsteadOfDeadLettered() {
        when(avroSerde.deserialize(anyString(), any()))
                .thenThrow(new AvroCodecUnavailableException("Avro codec pool exhausted after 250 ms"));

        AvroCodecUnavailableException thrown =
                assertThrows(AvroCodecUnavailableException.class, () -> consumer.receive(record()));

        assertEquals("Avro codec pool exhausted after 250 ms", thrown.getMessage());
        verify(deadLetters, never()).route(any(), anyString(), any());
    }

    @Test
    void aDlqThatCannotConfirmIsNeverTreatedAsAcknowledged() {
        when(avroSerde.deserialize(anyString(), any())).thenThrow(new IllegalStateException("bad magic byte"));
        doThrow(new IllegalStateException("dlq broker down"))
                .when(deadLetters).route(any(), anyString(), any());

        assertThrows(IllegalStateException.class, () -> consumer.receive(record()));
    }

    /**
     * RES-05: a Redis outage during the apply stage (successfully decoded, but the store write
     * fails) must never be silently acknowledged. The consumer already retries within its
     * budget and dead-letters instead of swallowing — this proves that specific path, which
     * previously only had decode-stage coverage.
     */
    @Test
    void anApplyFailureFromAStoreOutageExhaustsRetriesThenDeadLettersAndCommits() {
        when(avroSerde.deserialize(anyString(), any())).thenReturn(completedAvroEvent());
        when(store.get(anyString())).thenThrow(
                new StoreUnavailableException("Failed to read status for req-1", new RuntimeException("conn refused")));

        assertDoesNotThrow(() -> consumer.receive(record()));

        // maxAttempts=2 (see setUp): both attempts must have actually hit the store, not just one.
        verify(store, times(2)).get("req-1");
        verify(deadLetters).route(any(), eq(PaymentResponseConsumer.STAGE_APPLY), any(StoreUnavailableException.class));
    }

    @Test
    void anApplyFailureIsNeverAcknowledgedIfTheDlqAlsoFails() {
        when(avroSerde.deserialize(anyString(), any())).thenReturn(completedAvroEvent());
        when(store.get(anyString())).thenThrow(
                new StoreUnavailableException("Failed to read status for req-1", new RuntimeException("conn refused")));
        doThrow(new IllegalStateException("dlq broker down"))
                .when(deadLetters).route(any(), eq(PaymentResponseConsumer.STAGE_APPLY), any());

        assertThrows(IllegalStateException.class, () -> consumer.receive(record()));
    }
}
