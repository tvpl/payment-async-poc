package com.example.payments.api.filter;

import com.example.payments.api.config.SecurityProperties;
import com.example.payments.api.error.Problem;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.util.HashSet;
import java.util.Set;

/**
 * Authenticates business endpoints with an {@code X-API-Key} header (a simple, concrete
 * mechanism for the PoC). Management endpoints (health/metrics/swagger) are not covered by
 * this filter's path pattern. Production should use JWT/OAuth2 + mTLS.
 */
@Filter({"/payment-simulations", "/payment-simulations/**"})
public class ApiKeyFilter implements HttpServerFilter {

    private static final String HEADER = "X-API-Key";

    private final boolean enabled;
    private final Set<String> apiKeys;

    public ApiKeyFilter(SecurityProperties properties) {
        this.enabled = properties.isEnabled();
        this.apiKeys = new HashSet<>(properties.getApiKeys());
    }

    @Override
    public int getOrder() {
        return -20; // run before the rate limiter
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        if (!enabled) {
            return chain.proceed(request);
        }
        String key = request.getHeaders().get(HEADER);
        if (key != null && apiKeys.contains(key)) {
            return chain.proceed(request);
        }
        MutableHttpResponse<?> unauthorized = HttpResponse.status(HttpStatus.UNAUTHORIZED)
                .contentType(Problem.MEDIA_TYPE)
                .body(Problem.of(401, "Unauthorized", "Missing or invalid " + HEADER));
        return Publishers.just(unauthorized);
    }
}
