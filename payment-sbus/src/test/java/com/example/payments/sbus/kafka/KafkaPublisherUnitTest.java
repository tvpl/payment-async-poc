package com.example.payments.sbus.kafka;

import com.example.payments.sbus.config.OutboxProperties;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaPublisherUnitTest {

    @Test
    void boundedSendCancelsDeliveryWhenBrokerDoesNotComplete() throws Exception {
        Producer<String, byte[]> producer = mock(Producer.class);
        Future<RecordMetadata> delivery = mock(Future.class);
        when(producer.send(any())).thenReturn(delivery);
        when(delivery.get(10, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException("blocked"));
        OutboxProperties properties = new OutboxProperties();
        properties.setPublishTimeout(Duration.ofMillis(10));
        KafkaPublisher publisher = new KafkaPublisher(producer, properties);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> publisher.send("topic", "key", new byte[]{1}, Map.of()));

        assertTrue(failure.getMessage().contains("Timed out publishing"));
        verify(delivery).cancel(true);
    }
}
