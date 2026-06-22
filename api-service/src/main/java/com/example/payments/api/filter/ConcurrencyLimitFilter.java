package com.example.payments.api.filter;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import jakarta.inject.Named;
import org.reactivestreams.Publisher;

/**
 * Admission control for POST /payment-simulations. Virtual threads make waiting cheap
 * but do NOT bound load on the Core — this rate limiter does, returning 429 with
 * {@code Retry-After} when the burst exceeds the configured rate.
 */
@Filter(value = "/payment-simulations", methods = HttpMethod.POST)
public class ConcurrencyLimitFilter implements HttpServerFilter {

    private final RateLimiter rateLimiter;

    public ConcurrencyLimitFilter(@Named("api-admission") RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        if (!rateLimiter.acquirePermission()) {
            MutableHttpResponse<?> tooMany = HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1");
            return Publishers.just(tooMany);
        }
        return chain.proceed(request);
    }
}
