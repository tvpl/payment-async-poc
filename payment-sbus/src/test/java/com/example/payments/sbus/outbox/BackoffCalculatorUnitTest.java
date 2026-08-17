package com.example.payments.sbus.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * RES-01: a batch of outbox rows that failed together shares the same {@code attempts}
     * value — without jitter, {@link BackoffCalculator#backoff} would give every one of them the
     * exact same {@code next_attempt_at}, so they all become due at once and get reclaimed
     * together again (a thundering herd). Done-when: "Distribuição de N=100 atrasos com mesmo
     * attempts tem dispersão >= 20%".
     */
    @Test
    void appliesAtLeastTwentyPercentJitterAcrossABatchThatFailedTogether() {
        int attempts = 3;
        int samples = 100;
        long[] delays = new long[samples];
        for (int i = 0; i < samples; i++) {
            delays[i] = BackoffCalculator.backoffWithJitter(attempts, BASE, MAX).toMillis();
        }

        long min = Arrays.stream(delays).min().orElseThrow();
        long max = Arrays.stream(delays).max().orElseThrow();
        double mean = Arrays.stream(delays).average().orElseThrow();
        double dispersion = (max - min) / mean;

        assertTrue(dispersion >= 0.20,
                "dispersion across " + samples + " same-attempts delays must be >= 20%, was " + dispersion);
    }

    /** RES-01: "preservando teto" — jitter must never push a delay past the configured cap. */
    @Test
    void jitterNeverPushesTheDelayPastTheCap() {
        for (int i = 0; i < 100; i++) {
            Duration delay = BackoffCalculator.backoffWithJitter(30, BASE, MAX);
            assertTrue(delay.compareTo(MAX) <= 0, "jittered delay " + delay + " exceeded cap " + MAX);
        }
    }

    /** Jitter must not produce a negative or nonsensical delay. */
    @Test
    void jitterNeverProducesANegativeDelay() {
        for (int i = 0; i < 100; i++) {
            Duration delay = BackoffCalculator.backoffWithJitter(1, BASE, MAX);
            assertTrue(!delay.isNegative(), "jittered delay must not be negative: " + delay);
        }
    }
}
