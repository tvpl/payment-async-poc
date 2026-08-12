package com.example.payments.api.filter;

import com.example.payments.api.config.ApiRateLimiterFactory;
import com.example.payments.api.ratelimit.RedisRateLimiter;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Admission control for POST /payment-simulations, including its v0 beta at
 * {@code /v0/payment-simulations}. Virtual threads make waiting cheap but do NOT bound load on
 * the Core — these limiters do, returning 429 with {@code Retry-After} when the burst exceeds the
 * configured budget (CAP-03).
 *
 * <p>Two budgets apply: the resource budget caps the route across all callers, and the tenant
 * budget stops one caller from consuming the whole route. The tenant is identified by a hash of
 * its credential, never the credential itself, so no secret reaches a Redis key or a log line.
 * {@code resource} includes the path, so v0 gets its own resource bucket at the same configured
 * size as the main route rather than sharing one counter with it. v0 is unauthenticated by design
 * (see {@code V0PaymentSimulationController}), so it never carries an {@code X-API-Key} — every
 * anonymous v0 caller therefore shares one {@code "anonymous"} tenant bucket, same as any
 * anonymous caller would on the main route.
 */
@Filter(value = {"/payment-simulations", "/v0/payment-simulations"}, methods = HttpMethod.POST)
public class ConcurrencyLimitFilter implements HttpServerFilter {

    private static final String ANONYMOUS_TENANT = "anonymous";

    private final RedisRateLimiter resourceLimiter;
    private final RedisRateLimiter tenantLimiter;

    public ConcurrencyLimitFilter(
            @Named(ApiRateLimiterFactory.RESOURCE_LIMITER) RedisRateLimiter resourceLimiter,
            @Named(ApiRateLimiterFactory.TENANT_LIMITER) RedisRateLimiter tenantLimiter) {
        this.resourceLimiter = resourceLimiter;
        this.tenantLimiter = tenantLimiter;
    }

    @Override
    public int getOrder() {
        return -10; // run after auth
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String resource = request.getMethod().name() + ":" + request.getPath();
        if (!resourceLimiter.tryAcquire(resource) || !tenantLimiter.tryAcquire(tenant(request))) {
            return Publishers.just(HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1"));
        }
        return chain.proceed(request);
    }

    private static String tenant(HttpRequest<?> request) {
        String credential = request.getHeaders().get("X-API-Key");
        if (credential == null || credential.isBlank()) {
            return ANONYMOUS_TENANT;
        }
        return fingerprint(credential);
    }

    private static String fingerprint(String credential) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to identify a tenant", e);
        }
    }
}
