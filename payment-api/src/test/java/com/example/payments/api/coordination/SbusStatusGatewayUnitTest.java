package com.example.payments.api.coordination;

import com.example.payments.api.client.SbusStatusClient;
import com.example.payments.api.client.SbusStatusResponse;
import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The durable status fallback is best-effort: an unavailable SBUS degrades to "no extra
 * information" and stops costing the caller its timeout once the circuit trips (PAY-09).
 */
class SbusStatusGatewayUnitTest {

    private static final String SERVICE_NAME = "payment-simulation-api";

    private SbusStatusClient client;
    private SbusFallbackProperties properties;
    private SbusStatusGateway gateway;

    @BeforeEach
    void setUp() {
        client = mock(SbusStatusClient.class);
        properties = new SbusFallbackProperties();
        properties.setFailureThreshold(3);
        properties.setOpenDuration(Duration.ofMillis(400));
        gateway = new SbusStatusGateway(client, properties, SERVICE_NAME);
    }

    @Test
    void passesTheDurableStatusThroughAndIdentifiesTheCallingService() {
        SbusStatusResponse response = new SbusStatusResponse("req-1", "COMPLETED", null);
        when(client.getStatus("req-1", SERVICE_NAME)).thenReturn(Optional.of(response));

        Optional<SbusStatusResponse> result = gateway.getStatus("req-1");

        assertEquals(Optional.of(response), result);
        verify(client).getStatus("req-1", SERVICE_NAME);
    }

    @Test
    void anUnavailableSbusDegradesToNoInformationInsteadOfAnError() {
        when(client.getStatus(anyString(), anyString())).thenThrow(new RuntimeException("read timeout"));

        assertEquals(Optional.empty(), gateway.getStatus("req-1"));
    }

    @Test
    void repeatedFailuresOpenTheCircuitAndStopSpendingTheTimeout() {
        when(client.getStatus(anyString(), anyString())).thenThrow(new RuntimeException("read timeout"));

        for (int attempt = 0; attempt < 3; attempt++) {
            gateway.getStatus("req-" + attempt);
        }
        assertTrue(gateway.circuitOpen());

        assertEquals(Optional.empty(), gateway.getStatus("req-after-open"));
        verify(client, never()).getStatus(eq("req-after-open"), anyString());
    }

    @Test
    void theCircuitClosesAgainAfterItsOpenDuration() throws InterruptedException {
        when(client.getStatus(anyString(), anyString())).thenThrow(new RuntimeException("read timeout"));
        for (int attempt = 0; attempt < 3; attempt++) {
            gateway.getStatus("req-" + attempt);
        }

        Thread.sleep(500);

        assertFalse(gateway.circuitOpen());
        gateway.getStatus("req-recheck");
        verify(client).getStatus(eq("req-recheck"), anyString());
    }

    @Test
    void anInterveningSuccessKeepsTheCircuitClosed() {
        when(client.getStatus(eq("fail-1"), anyString())).thenThrow(new RuntimeException("read timeout"));
        when(client.getStatus(eq("fail-2"), anyString())).thenThrow(new RuntimeException("read timeout"));
        when(client.getStatus(eq("ok"), anyString())).thenReturn(Optional.empty());

        gateway.getStatus("fail-1");
        gateway.getStatus("fail-2");
        gateway.getStatus("ok");
        gateway.getStatus("fail-1");
        gateway.getStatus("fail-2");

        assertFalse(gateway.circuitOpen(), "isolated failures must not trip the circuit");
        verify(client, times(2)).getStatus(eq("fail-1"), anyString());
    }

    @Test
    void rejectsAFailurePolicyThatCouldNeverBound() {
        SbusFallbackProperties invalid = new SbusFallbackProperties();
        invalid.setOpenDuration(Duration.ZERO);

        assertThrows(ConfigurationException.class, invalid::validate);
    }

    @Test
    void rejectsAFailureThresholdBelowOne() {
        SbusFallbackProperties invalid = new SbusFallbackProperties();
        invalid.setFailureThreshold(0);

        assertThrows(ConfigurationException.class, invalid::validate);
    }
}
