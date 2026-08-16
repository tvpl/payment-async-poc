package com.example.payments.api.filter;

import com.example.payments.api.config.SecurityProperties;
import com.example.payments.api.error.Problem;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.HashSet;
import java.util.Set;

/**
 * Authenticates business endpoints with an {@code X-API-Key} header (a simple, concrete
 * mechanism for the PoC). Management endpoints (health/metrics/swagger) are not covered by
 * this filter's path pattern. Production should use JWT/OAuth2 + mTLS.
 *
 * <p>Runs on {@link TaskExecutors#BLOCKING} (BUDG-03): filter methods execute on the Netty
 * event loop by default, and this method is the admission gate ahead of the rate limiter's
 * Redis calls - it must never share that fate, even though today's check itself is in-memory.
 */
@ServerFilter({"/payment-simulations", "/payment-simulations/**"})
@Order(-20) // run before the rate limiter
public class ApiKeyFilter {

    private static final String HEADER = "X-API-Key";

    private final boolean enabled;
    private final Set<String> apiKeys;

    public ApiKeyFilter(SecurityProperties properties) {
        this.enabled = properties.isEnabled();
        this.apiKeys = new HashSet<>(properties.getApiKeys());
    }

    /** Returns {@code null} to proceed, or a response to short-circuit the chain. */
    @RequestFilter
    @ExecuteOn(TaskExecutors.BLOCKING)
    public @Nullable MutableHttpResponse<?> filterRequest(HttpRequest<?> request) {
        if (!enabled) {
            return null;
        }
        String key = request.getHeaders().get(HEADER);
        if (key != null && apiKeys.contains(key)) {
            return null;
        }
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                .contentType(Problem.MEDIA_TYPE)
                .body(Problem.of(401, "Unauthorized", "Missing or invalid " + HEADER));
    }
}
