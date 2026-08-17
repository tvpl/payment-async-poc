package com.example.payments.sbus.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with a cap. Extracted so it can be unit-tested in isolation. */
public final class BackoffCalculator {

    private BackoffCalculator() {
    }

    public static Duration backoff(int attempts, Duration base, Duration max) {
        long baseMillis = base.toMillis();
        long millis = baseMillis * (1L << Math.min(Math.max(attempts - 1, 0), 16));
        return Duration.ofMillis(Math.min(millis, max.toMillis()));
    }

    /**
     * Same exponential curve as {@link #backoff}, with jitter of roughly ±30% applied on top
     * (RES-01) — independently randomized on every call, so a whole batch of outbox rows that
     * failed together (same {@code attempts}) does not all become due at the exact same instant,
     * which would just reclaim them into another thundering herd. The cap is re-applied after
     * jitter so a delay can never exceed {@code max}, even though jitter can also push it below.
     */
    public static Duration backoffWithJitter(int attempts, Duration base, Duration max) {
        long capped = backoff(attempts, base, max).toMillis();
        long jittered = Math.round(capped * jitterFactor());
        return Duration.ofMillis(Math.min(Math.max(0, jittered), max.toMillis()));
    }

    /** Uniformly distributed in [0.7, 1.3) — at least ±20%, within the ±20-50% design band. */
    private static double jitterFactor() {
        return 0.7 + ThreadLocalRandom.current().nextDouble() * 0.6;
    }
}
