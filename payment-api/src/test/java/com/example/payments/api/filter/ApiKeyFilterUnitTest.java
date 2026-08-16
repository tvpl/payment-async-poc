package com.example.payments.api.filter;

import com.example.payments.api.config.SecurityProperties;
import com.example.payments.api.error.Problem;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUDG-03: {@code ApiKeyFilter} moved from {@code @Filter}/{@code HttpServerFilter} (Netty event
 * loop by default) to a {@code @RequestFilter} method wired to {@link TaskExecutors#BLOCKING}, so
 * the admission gate never shares the event loop with {@code /health/liveness}. Preserves the
 * exact 401/pass-through semantics of the migrated filter (T5's behavior, unchanged).
 */
class ApiKeyFilterUnitTest {

    private static final String HEADER = "X-API-Key";
    private static final String VALID_KEY = "valid-key";

    private final ApiKeyFilter filter = filterWith(true, VALID_KEY);

    @Test
    void aValidKeyProceeds() {
        MutableHttpResponse<?> result = filter.filterRequest(HttpRequest.GET("/payment-simulations").header(HEADER, VALID_KEY));

        assertNull(result, "a valid key must proceed (null short-circuits nothing)");
    }

    @Test
    void aMissingKeyIsRejectedWithProblemJson() {
        MutableHttpResponse<?> result = filter.filterRequest(HttpRequest.GET("/payment-simulations"));

        assertEquals(HttpStatus.UNAUTHORIZED, result.getStatus());
        assertEquals(Problem.MEDIA_TYPE, result.getContentType().orElseThrow().toString());
        Problem body = (Problem) result.getBody().orElseThrow();
        assertEquals(401, body.status());
    }

    @Test
    void anInvalidKeyIsRejected() {
        MutableHttpResponse<?> result =
                filter.filterRequest(HttpRequest.GET("/payment-simulations").header(HEADER, "wrong-key"));

        assertEquals(HttpStatus.UNAUTHORIZED, result.getStatus());
    }

    @Test
    void aDisabledFilterAlwaysProceedsEvenWithoutAKey() {
        ApiKeyFilter disabled = filterWith(false, VALID_KEY);

        assertNull(disabled.filterRequest(HttpRequest.GET("/payment-simulations")));
    }

    /**
     * BUDG-03: the request-filter method must be wired off the Netty event loop. A regression
     * that drops {@code @ExecuteOn(TaskExecutors.BLOCKING)} (reverting to the event-loop default)
     * fails this test even though the 401/pass-through behavior above would still be green.
     */
    @Test
    void theRequestFilterMethodRunsOnTheBlockingExecutorNotTheEventLoop() throws NoSuchMethodException {
        Method filterMethod = ApiKeyFilter.class.getMethod("filterRequest", HttpRequest.class);

        assertTrue(filterMethod.isAnnotationPresent(RequestFilter.class),
                "filterRequest must be a @RequestFilter method");
        ExecuteOn executeOn = filterMethod.getAnnotation(ExecuteOn.class);
        assertEquals(TaskExecutors.BLOCKING, executeOn.value(),
                "filterRequest must run on TaskExecutors.BLOCKING, never the default event loop");
    }

    private static ApiKeyFilter filterWith(boolean enabled, String... keys) {
        SecurityProperties properties = new SecurityProperties();
        properties.setEnabled(enabled);
        properties.setApiKeys(List.of(keys));
        return new ApiKeyFilter(properties);
    }
}
