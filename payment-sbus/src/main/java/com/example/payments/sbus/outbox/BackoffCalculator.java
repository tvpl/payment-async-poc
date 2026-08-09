package com.example.payments.sbus.outbox;

import java.time.Duration;

/** Exponential backoff with a cap. Extracted so it can be unit-tested in isolation. */
public final class BackoffCalculator {

    private BackoffCalculator() {
    }

    public static Duration backoff(int attempts, Duration base, Duration max) {
        long baseMillis = base.toMillis();
        long millis = baseMillis * (1L << Math.min(Math.max(attempts - 1, 0), 16));
        return Duration.ofMillis(Math.min(millis, max.toMillis()));
    }
}
