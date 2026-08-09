package com.example.platform.featurecontrol.source;

import java.util.Random;

/**
 * FTR-02: spreads per-key cache expiry over a window instead of a single instant, so many flags
 * cached at the same moment (e.g. right after startup) don't all expire in lockstep and thunder-herd
 * Redis at once. Pure function, seedable {@link Random} so tests are deterministic.
 */
public final class CacheJitter {

    private CacheJitter() {
    }

    /**
     * @param baseMillis     the configured TTL
     * @param jitterFraction how much to vary the TTL by, as a fraction of {@code baseMillis};
     *                       clamped to {@code [0,1]}
     * @param random         the randomness source (inject a seeded {@link Random} in tests)
     * @return a value in {@code [baseMillis * (1 - jitterFraction), baseMillis * (1 + jitterFraction)]},
     *         never negative
     */
    public static long jittered(long baseMillis, double jitterFraction, Random random) {
        if (baseMillis <= 0) {
            return 0;
        }
        double clamped = Math.max(0, Math.min(1, jitterFraction));
        double delta = (random.nextDouble() * 2 - 1) * clamped;
        long result = Math.round(baseMillis * (1 + delta));
        return Math.max(0, result);
    }
}
