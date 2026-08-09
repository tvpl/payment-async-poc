package com.example.payments.sbus.metrics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SbusAlertContractTest {

    @Test
    void localAlertUsesPendingCountAndOldestAgeThreshold() throws Exception {
        String rule = Files.readString(Path.of("ops/alerts/recoverable-dlq.yml"));

        assertTrue(rule.contains("alert: PaymentSbusRecoverableDlqStuck"));
        assertTrue(rule.contains(
                "sbus_dlq_unconfirmed > 0 and sbus_dlq_unconfirmed_oldest_age_seconds > 300"));
        assertTrue(rule.contains("for: 5m"));
        assertTrue(rule.contains("severity: critical"));
    }
}
