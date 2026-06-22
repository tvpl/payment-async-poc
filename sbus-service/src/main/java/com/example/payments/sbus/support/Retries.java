package com.example.payments.sbus.support;

import java.time.Duration;

/** Bounded in-process retry for transient failures before routing to the DLQ. */
public final class Retries {

    private Retries() {
    }

    public static void run(int maxAttempts, Duration delay, Runnable action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                action.run();
                return;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }
}
