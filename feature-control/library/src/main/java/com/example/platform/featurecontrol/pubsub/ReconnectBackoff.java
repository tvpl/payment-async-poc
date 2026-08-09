package com.example.platform.featurecontrol.pubsub;

import java.time.Duration;
import java.util.Random;

/**
 * FTR-03: "reconectar com jitter" — capped exponential backoff with jitter for the pub/sub
 * reconnect loop, so a Redis outage doesn't have every app instance hammering it in lockstep the
 * instant it comes back. Pure function, seedable {@link Random} so tests are deterministic.
 */
public final class ReconnectBackoff {

    private ReconnectBackoff() {
    }

    /**
     * @param attempt the 1-based retry attempt number (values below 1 are treated as 1)
     * @param base    the minimum delay (returned when {@code max} does not allow any growth)
     * @param max     the delay ceiling regardless of attempt
     * @param random  the randomness source (inject a seeded {@link Random} in tests)
     * @return a value in {@code [base, min(max, base * 2^attempt)]}
     */
    public static Duration nextDelay(int attempt, Duration base, Duration max, Random random) {
        int effectiveAttempt = Math.max(1, attempt);
        long baseMillis = Math.max(0, base.toMillis());
        long maxMillis = Math.max(baseMillis, max.toMillis());
        int shift = Math.min(effectiveAttempt, 20); // avoid overflow on a runaway attempt counter
        long upperBound = baseMillis <= 0
                ? maxMillis
                : Math.min(maxMillis, baseMillis * (1L << shift));

        if (upperBound <= baseMillis) {
            return Duration.ofMillis(baseMillis);
        }
        long span = upperBound - baseMillis;
        long jittered = baseMillis + (long) (random.nextDouble() * span);
        return Duration.ofMillis(jittered);
    }
}
