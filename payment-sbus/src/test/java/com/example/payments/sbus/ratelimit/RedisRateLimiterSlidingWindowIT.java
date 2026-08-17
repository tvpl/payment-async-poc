package com.example.payments.sbus.ratelimit;

import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T33/SCAL-06 (mirrors payment-api's RedisRateLimiterSlidingWindowIT): the sliding-window
 * admission estimate and the EVALSHA/NOSCRIPT reload path both run inside a Lua script executed
 * server-side, so they are only meaningfully provable against a real Redis - unlike
 * {@link RedisRateLimiterUnitTest}, which mocks the script's result.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRateLimiterSlidingWindowIT {

    private static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;

    @BeforeAll
    void start() {
        REDIS.start();
        redisClient = RedisClient.create(REDIS.getRedisURI());
        connection = redisClient.connect();
    }

    @AfterAll
    void stop() {
        connection.close();
        redisClient.shutdown();
    }

    @BeforeEach
    void resetBudgets() {
        connection.sync().flushall();
    }

    /**
     * A bare fixed window would admit all 40 (20 fully within each of two adjacent windows,
     * straddling the instant of the boundary); the sliding-window estimate weights the outgoing
     * window's count into the incoming window's decision, so the combined total across the
     * boundary stays well under 2x the budget.
     */
    @Test
    void requestsStraddlingAWindowBoundaryDoNotAdmitDoubleTheBudget() {
        long windowMillis = 3000L;
        int limit = 20;
        RedisRateLimiter limiter = new RedisRateLimiter(connection::sync, "boundary-test", limit, windowMillis);

        // Land comfortably inside the current window, then exhaust its budget.
        sleepUntilMillisBeforeBoundary(windowMillis, 800);
        int admittedFirstBatch = admitAsManyAsPossible(limiter, limit);

        // Cross into the next window while it is still fresh, and immediately retry the budget.
        sleepPastBoundary(windowMillis, 50);
        int admittedSecondBatch = admitAsManyAsPossible(limiter, limit);

        assertEquals(limit, admittedFirstBatch, "the first window's own full budget should be admitted");
        assertTrue(admittedFirstBatch + admittedSecondBatch < 2 * limit,
                "requests straddling the window boundary must not admit 2x the budget: "
                        + admittedFirstBatch + " admitted before the boundary, "
                        + admittedSecondBatch + " admitted right after it");
    }

    /**
     * A {@code SCRIPT FLUSH} after the limiter has already cached its SHA reproduces exactly the
     * NOSCRIPT reply EVALSHA gets from a Redis node that never loaded the script (a restart, or
     * this instance's first call against a fresh node). The very call that triggers the reload
     * must still get a real admission decision, not an automatic denial.
     */
    @Test
    void aScriptFlushForcingNoScriptStillAdmitsTheTriggeringRequest() {
        RedisRateLimiter limiter = new RedisRateLimiter(connection::sync, "noscript-test", 10, 60_000L);
        assertTrue(limiter.tryAcquire(), "warm-up call should be admitted (loads the script)");

        connection.sync().scriptFlush();

        assertTrue(limiter.tryAcquire(),
                "the call that hits NOSCRIPT after a flush must still be admitted (well under budget), "
                        + "not denied just because the script had to reload");
    }

    private static int admitAsManyAsPossible(RedisRateLimiter limiter, int attempts) {
        int admitted = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire()) {
                admitted++;
            }
        }
        return admitted;
    }

    private static void sleepUntilMillisBeforeBoundary(long windowMillis, long marginMillis) {
        long elapsed = Math.floorMod(System.currentTimeMillis(), windowMillis);
        long untilBoundary = windowMillis - elapsed;
        sleep(Math.max(0, untilBoundary - marginMillis));
    }

    private static void sleepPastBoundary(long windowMillis, long extraMillis) {
        long elapsed = Math.floorMod(System.currentTimeMillis(), windowMillis);
        long untilBoundary = windowMillis - elapsed;
        sleep(untilBoundary + extraMillis);
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
