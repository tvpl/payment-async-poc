package com.example.payments.api.coordination;

import com.example.payments.api.client.SbusStatusClient;
import com.example.payments.api.client.SbusStatusResponse;
import com.example.payments.api.metrics.ApiMetrics;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded gateway to the SBUS durable status endpoint (PAY-09).
 *
 * <p>The lookup is a best-effort fallback on the status path: it may add information, but it
 * must never cost more than its budget. The HTTP client bounds a single call
 * ({@code micronaut.http.services.sbus} connect/read timeouts); this circuit bounds the
 * <em>repeated</em> cost, so a persistently failing SBUS stops charging every request that
 * timeout. An unavailable SBUS degrades to "no extra information", never to an error.
 */
@Singleton
public class SbusStatusGateway {

    private static final Logger LOG = LoggerFactory.getLogger(SbusStatusGateway.class);

    private final SbusStatusClient client;
    private final SbusFallbackProperties properties;
    private final ApiMetrics metrics;
    private final String serviceName;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntilEpochMillis;

    public SbusStatusGateway(SbusStatusClient client,
                             SbusFallbackProperties properties,
                             ApiMetrics metrics,
                             @Value("${micronaut.application.name:payment-simulation-api}") String serviceName) {
        this.client = client;
        this.properties = properties;
        this.metrics = metrics;
        this.serviceName = serviceName;
    }

    public Optional<SbusStatusResponse> getStatus(String requestId) {
        if (circuitOpen()) {
            LOG.debug("SBUS status fallback skipped for {}: circuit open", requestId);
            return Optional.empty();
        }
        try {
            Optional<SbusStatusResponse> response = client.getStatus(requestId, serviceName);
            consecutiveFailures.set(0);
            return response;
        } catch (HttpClientResponseException e) {
            // SEC-05: a rejected service credential still degrades to "no extra information"
            // (never an error the caller sees), but it is a distinct failure mode from a slow or
            // unreachable SBUS - worth its own signal so a misconfigured/expired credential is
            // visible instead of silently looking like ordinary SBUS unavailability.
            if (isAuthFailure(e.getStatus())) {
                metrics.recordSbusAuthFailure();
            }
            recordFailure();
            LOG.debug("SBUS status fallback unavailable for {}: {}", requestId, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            recordFailure();
            LOG.debug("SBUS status fallback unavailable for {}: {}", requestId, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isAuthFailure(HttpStatus status) {
        return status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN;
    }

    /** True while the circuit is open. Exposed for readiness/diagnostics. */
    public boolean circuitOpen() {
        return System.currentTimeMillis() < openUntilEpochMillis;
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= properties.getFailureThreshold()) {
            openUntilEpochMillis = System.currentTimeMillis() + properties.getOpenDuration().toMillis();
            consecutiveFailures.set(0);
            LOG.warn("SBUS status fallback circuit opened for {}", properties.getOpenDuration());
        }
    }
}
