package com.example.payments.sbus.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackoffCalculatorUnitTest {

    private static final Duration BASE = Duration.ofSeconds(2);
    private static final Duration MAX = Duration.ofMinutes(5);

    @Test
    void growsExponentiallyFromBase() {
        assertEquals(Duration.ofSeconds(2), BackoffCalculator.backoff(1, BASE, MAX));
        assertEquals(Duration.ofSeconds(4), BackoffCalculator.backoff(2, BASE, MAX));
        assertEquals(Duration.ofSeconds(8), BackoffCalculator.backoff(3, BASE, MAX));
        assertEquals(Duration.ofSeconds(16), BackoffCalculator.backoff(4, BASE, MAX));
    }

    @Test
    void isCappedAtMax() {
        assertEquals(MAX, BackoffCalculator.backoff(20, BASE, MAX));
    }
}
