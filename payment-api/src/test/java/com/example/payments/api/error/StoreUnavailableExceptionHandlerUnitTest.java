package com.example.payments.api.error;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RES-01/RES-04: a Redis outage must surface as a controlled 503, never the raw Lettuce
 * connection failure (host, port, driver exception text) that would otherwise leak the API's
 * internal network topology to an anonymous caller.
 */
class StoreUnavailableExceptionHandlerUnitTest {

    private final StoreUnavailableExceptionHandler handler = new StoreUnavailableExceptionHandler();

    @Test
    void aStoreOutageMapsTo503WithAProblemJsonBody() throws Exception {
        StoreUnavailableException exception = new StoreUnavailableException(
                "Failed to reach the store",
                new RuntimeException("Unable to connect to redis/<unresolved>:6379"));

        HttpResponse<Problem> response = handler.handle(null, exception);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatus());
        assertEquals("1", response.getHeaders().get("Retry-After"));
        Problem body = response.body();
        assertEquals(503, body.status());

        String serialized = ObjectMapper.getDefault().writeValueAsString(body);
        assertFalse(serialized.toLowerCase().contains("redis"),
                "response body must not mention the infrastructure component: " + serialized);
        assertFalse(serialized.contains("6379"), "response body must not leak a port: " + serialized);
        assertFalse(serialized.contains("unresolved"), "response body must not leak connection internals: " + serialized);
    }

    @Test
    void theOriginalCauseIsNeverPresentInTheResponseBodyOnlyLoggable() throws Exception {
        RuntimeException cause = new RuntimeException("connect timed out to redis-primary.internal:6379");
        StoreUnavailableException exception = new StoreUnavailableException("Failed to reach the store", cause);

        HttpResponse<Problem> response = handler.handle(null, exception);

        String serialized = ObjectMapper.getDefault().writeValueAsString(response.body());
        assertFalse(serialized.contains(cause.getMessage()),
                "the raw cause message must never reach the HTTP body: " + serialized);
        assertTrue(response.body().detail() != null && !response.body().detail().isBlank(),
                "the body must still explain the failure to the caller in generic terms");
    }
}
