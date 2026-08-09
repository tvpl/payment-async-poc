package com.example.payments.api.kafka;

import com.example.payments.api.coordination.ResponseCoordinator;
import com.example.payments.api.metrics.ApiMetrics;
import com.example.payments.api.redis.RedisStatusStore;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroCodecUnavailableException;
import com.example.payments.common.kafka.AvroSerde;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private PaymentResponseConsumer consumer;

    @BeforeEach
    void setUp() {
        avroSerde = mock(AvroSerde.class);
        deadLetters = mock(ResponseDeadLetters.class);
        ResponseConsumerProperties properties = new ResponseConsumerProperties();
        properties.setMaxAttempts(2);
        properties.setRetryDelay(Duration.ZERO);
        consumer = new PaymentResponseConsumer(
                mock(RedisStatusStore.class), mock(ResponseCoordinator.class), avroSerde,
                mock(ApiMetrics.class), deadLetters, properties);
    }

    private static ConsumerRecord<String, byte[]> record() {
        return new ConsumerRecord<>(Topics.COMPLETED, 0, 7L, "req-1", new byte[]{9, 9});
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
}
