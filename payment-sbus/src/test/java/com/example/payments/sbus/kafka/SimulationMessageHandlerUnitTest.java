package com.example.payments.sbus.kafka;

import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroCodecUnavailableException;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.sbus.service.PaymentSimulationService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationMessageHandlerUnitTest {

    @Test
    void registryCapacityFailureRemainsRetryableOnTheKafkaRecord() {
        AvroSerde serde = mock(AvroSerde.class);
        byte[] payload = {1};
        AvroCodecUnavailableException unavailable =
                new AvroCodecUnavailableException("registry codec unavailable");
        when(serde.deserialize(Topics.REQUESTED, payload)).thenThrow(unavailable);
        var handler = new SimulationMessageHandler(serde, mock(PaymentSimulationService.class));

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> handler.handle(Topics.REQUESTED, payload, Map.of()));

        assertSame(unavailable, actual);
    }
}
