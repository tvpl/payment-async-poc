package com.example.payments.api.filter;

import com.example.payments.api.config.ApiRateLimiterFactory;
import com.example.payments.api.ratelimit.RedisRateLimiter;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Named;

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
 *
 * <p>Runs on {@link TaskExecutors#BLOCKING} (BUDG-03): the dual-budget check below is a
 * synchronous Redis round-trip (Lettuce sync commands), and filter methods execute on the Netty
 * event loop by default — a slow Redis would otherwise stall every connection the event loop
 * serves, including {@code /health/liveness} (BUDG-04).
 */
@ServerFilter(value = {"/payment-simulations", "/v0/payment-simulations"}, methods = HttpMethod.POST)
@Order(-10) // run after auth
public class ConcurrencyLimitFilter {

    private static final String ANONYMOUS_TENANT = "anonymous";

    private final RedisRateLimiter resourceLimiter;
    private final RedisRateLimiter tenantLimiter;

    public ConcurrencyLimitFilter(
            @Named(ApiRateLimiterFactory.RESOURCE_LIMITER) RedisRateLimiter resourceLimiter,
            @Named(ApiRateLimiterFactory.TENANT_LIMITER) RedisRateLimiter tenantLimiter) {
        this.resourceLimiter = resourceLimiter;
        this.tenantLimiter = tenantLimiter;
    }

    /** Returns {@code null} to proceed, or a response to short-circuit the chain. */
    @RequestFilter
    @ExecuteOn(TaskExecutors.BLOCKING)
    public @Nullable MutableHttpResponse<?> filterRequest(HttpRequest<?> request) {
        String resource = request.getMethod().name() + ":" + request.getPath();
        if (!resourceLimiter.tryAcquireBoth(resource, tenantLimiter, tenant(request))) {
            return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1");
        }
        return null;
    }

    /**
     * v0 is anonymous by design and not covered by {@code ApiKeyFilter}
     * ({@code /v0/payment-simulations} is unauthenticated), so any {@code X-API-Key} it carries
     * is unvalidated caller-supplied text. Fingerprinting it anyway would let a rotating key
     * mint a fresh tenant bucket - starting with a full budget - on every request, bypassing
     * the tenant budget entirely (AUD-05). Every v0 caller shares the fixed anonymous bucket
     * regardless of what the header says.
     */
    private static String tenant(HttpRequest<?> request) {
        if (request.getPath().startsWith("/v0/")) {
            return ANONYMOUS_TENANT;
        }
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
