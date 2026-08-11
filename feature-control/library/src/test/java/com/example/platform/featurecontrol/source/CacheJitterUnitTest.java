package com.example.platform.featurecontrol.source;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTR-02: "Expiração usa single-flight/jitter para evitar cache stampede." Pure boundary/spread
 * tests for the jitter function {@link RedisFlagSource} applies to each key's cache TTL.
 */
class CacheJitterUnitTest {

    @Test
    void neverExceedsTheUpperBound() {
        Random alwaysMax = new Random() {
            @Override
            public double nextDouble() {
                return 1.0; // maximum possible draw -> +jitterFraction
            }
        };
        long result = CacheJitter.jittered(10_000, 0.2, alwaysMax);
        assertEquals(12_000, result);
    }

    @Test
    void neverGoesBelowTheLowerBound() {
        Random alwaysMin = new Random() {
            @Override
            public double nextDouble() {
                return 0.0; // minimum possible draw -> -jitterFraction
            }
        };
        long result = CacheJitter.jittered(10_000, 0.2, alwaysMin);
        assertEquals(8_000, result);
    }

    @Test
    void zeroJitterFractionReturnsTheBaseValueExactly() {
        long result = CacheJitter.jittered(10_000, 0.0, new Random());
        assertEquals(10_000, result);
    }

    @Test
    void negativeJitterFractionIsClampedToZero() {
        long result = CacheJitter.jittered(10_000, -5.0, new Random());
        assertEquals(10_000, result, "a nonsensical negative fraction must not amplify the spread");
    }

    @Test
    void jitterFractionAboveOneIsClampedToOne() {
        Random alwaysMax = new Random() {
            @Override
            public double nextDouble() {
                return 1.0;
            }
        };
        long result = CacheJitter.jittered(10_000, 5.0, alwaysMax);
        assertEquals(20_000, result, "clamped to fraction=1.0, so the max spread is +100%");
    }

    @Test
    void neverReturnsNegativeEvenAtMaxNegativeJitter() {
        Random alwaysMin = new Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }
        };
        long result = CacheJitter.jittered(100, 5.0, alwaysMin);
        assertTrue(result >= 0, "a jittered TTL must never go negative");
    }

    @Test
    void zeroOrNegativeBaseAlwaysReturnsZero() {
        assertEquals(0, CacheJitter.jittered(0, 0.5, new Random()));
        assertEquals(0, CacheJitter.jittered(-1, 0.5, new Random()));
    }

    @Test
    void differentRandomDrawsSpreadDifferentKeysAcrossTheWindow() {
        // Two different seeds landing at different points in the window is what actually prevents
        // every key from expiring in lockstep — the real defense against a thundering herd.
        long a = CacheJitter.jittered(10_000, 0.5, new Random(1));
        long b = CacheJitter.jittered(10_000, 0.5, new Random(2));
        assertTrue(a != b, "distinct random sources should (with overwhelming probability) diverge");
    }
}
