package com.example.payments.api.filter;

import com.example.payments.api.ratelimit.RedisRateLimiter;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUDG-03: {@code ConcurrencyLimitFilter} moved from {@code @Filter}/{@code HttpServerFilter} to a
 * {@code @RequestFilter} method wired to {@link TaskExecutors#BLOCKING}. Its synchronous Redis
 * round-trip is the actual blocking-I/O offender (BUDG-04 depends on it never running on the
 * event loop); the admit/reject decision itself is unchanged from before the migration.
 */
class ConcurrencyLimitFilterUnitTest {

    @Test
    void anAdmittedRequestProceeds() {
        ConcurrencyLimitFilter filter = filterWithDegradedBudgetOfOne();

        MutableHttpResponse<?> result = filter.filterRequest(request());

        assertNull(result, "an admitted request must proceed (null short-circuits nothing)");
    }

    /**
     * No Redis in this unit test, so both limiters fall back to their per-instance degraded
     * budget (always {@code >= 1}, see {@code RedisRateLimiter}); the first call consumes that
     * one slot, so the second call on the same resource+tenant scope must be denied.
     */
    @Test
    void aDeniedRequestIsRejectedWith429AndRetryAfter() {
        ConcurrencyLimitFilter filter = filterWithDegradedBudgetOfOne();
        HttpRequest<?> request = request();
        filter.filterRequest(request);

        MutableHttpResponse<?> result = filter.filterRequest(request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, result.getStatus());
        assertEquals("1", result.getHeaders().get("Retry-After"));
    }

    /**
     * BUDG-03: the request-filter method must be wired off the Netty event loop. A regression
     * that drops {@code @ExecuteOn(TaskExecutors.BLOCKING)} fails this test even though the
     * admit/reject behavior above would still be green.
     */
    @Test
    void theRequestFilterMethodRunsOnTheBlockingExecutorNotTheEventLoop() throws NoSuchMethodException {
        Method filterMethod = ConcurrencyLimitFilter.class.getMethod("filterRequest", HttpRequest.class);

        assertTrue(filterMethod.isAnnotationPresent(RequestFilter.class),
                "filterRequest must be a @RequestFilter method");
        ExecuteOn executeOn = filterMethod.getAnnotation(ExecuteOn.class);
        assertEquals(TaskExecutors.BLOCKING, executeOn.value(),
                "filterRequest must run on TaskExecutors.BLOCKING, never the default event loop");
    }

    private static HttpRequest<?> request() {
        return HttpRequest.POST("/payment-simulations", "{}").header("X-API-Key", "tenant-a");
    }

    private static ConcurrencyLimitFilter filterWithDegradedBudgetOfOne() {
        return new ConcurrencyLimitFilter(redisLessLimiter(), redisLessLimiter());
    }

    /** No Redis backing (eval throws), so every call falls back to the local degraded budget. */
    private static RedisRateLimiter redisLessLimiter() {
        return new RedisRateLimiter(
                () -> {
                    throw new IllegalStateException("Redis unavailable in this unit test");
                },
                "test", 1, 1, 60_000L);
    }
}
