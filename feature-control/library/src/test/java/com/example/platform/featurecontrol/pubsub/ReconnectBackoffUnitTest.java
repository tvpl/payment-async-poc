package com.example.platform.featurecontrol.pubsub;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectBackoffUnitTest {

    private static final Duration BASE = Duration.ofMillis(200);
    private static final Duration MAX = Duration.ofSeconds(30);

    @Test
    void neverGoesBelowTheBaseDelay() {
        Random alwaysMin = fixed(0.0);
        Duration delay = ReconnectBackoff.nextDelay(1, BASE, MAX, alwaysMin);
        assertEquals(BASE, delay);
    }

    @Test
    void neverExceedsTheMaxDelayEvenAtAHighAttemptCount() {
        Random alwaysMax = fixed(1.0);
        Duration delay = ReconnectBackoff.nextDelay(50, BASE, MAX, alwaysMax);
        assertTrue(delay.compareTo(MAX) <= 0, "delay " + delay + " must not exceed max " + MAX);
    }

    @Test
    void upperBoundGrowsWithAttemptNumber() {
        Random alwaysMax = fixed(1.0);
        Duration attempt1 = ReconnectBackoff.nextDelay(1, BASE, MAX, alwaysMax);
        Duration attempt3 = ReconnectBackoff.nextDelay(3, BASE, MAX, alwaysMax);
        assertTrue(attempt3.compareTo(attempt1) > 0,
                "later attempts should allow a larger delay: " + attempt1 + " vs " + attempt3);
    }

    @Test
    void attemptBelowOneIsTreatedAsOne() {
        Random alwaysMin = fixed(0.0);
        assertEquals(ReconnectBackoff.nextDelay(1, BASE, MAX, alwaysMin),
                ReconnectBackoff.nextDelay(0, BASE, MAX, alwaysMin));
        assertEquals(ReconnectBackoff.nextDelay(1, BASE, MAX, alwaysMin),
                ReconnectBackoff.nextDelay(-5, BASE, MAX, alwaysMin));
    }

    @Test
    void differentRandomDrawsSpreadReconnectAttemptsAcrossTheWindow() {
        Duration a = ReconnectBackoff.nextDelay(5, BASE, MAX, new Random(1));
        Duration b = ReconnectBackoff.nextDelay(5, BASE, MAX, new Random(2));
        assertTrue(!a.equals(b), "distinct random sources should (with overwhelming probability) diverge");
    }

    @Test
    void alwaysStaysWithinBaseAndMaxAcrossManyAttempts() {
        Random random = new Random(42);
        for (int attempt = 1; attempt <= 30; attempt++) {
            Duration delay = ReconnectBackoff.nextDelay(attempt, BASE, MAX, random);
            assertTrue(delay.compareTo(BASE) >= 0 && delay.compareTo(MAX) <= 0,
                    "attempt " + attempt + " delay " + delay + " out of [" + BASE + "," + MAX + "]");
        }
    }

    private static Random fixed(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }
}
