package com.example.platform.asyncredis.api;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;

import java.util.HashSet;
import java.util.Set;

/**
 * Authenticates the job endpoints with an {@code X-API-Key} header (RED-08). Management endpoints
 * (health/metrics) are outside this filter's path pattern.
 */
@Filter({"/jobs", "/jobs/**"})
public class ApiKeyFilter implements HttpServerFilter {

    public static final String HEADER = "X-API-Key";

    private final boolean enabled;
    private final Set<String> apiKeys;

    public ApiKeyFilter(AsyncSecurityProperties properties) {
        this.enabled = properties.isEnabled();
        this.apiKeys = new HashSet<>(properties.getApiKeys());
    }

    @Override
    public int getOrder() {
        return -20; // run before admission control
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
        return Publishers.just(HttpResponse.status(HttpStatus.UNAUTHORIZED)
                .body("Missing or invalid " + HEADER));
    }
}
