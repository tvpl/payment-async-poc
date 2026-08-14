package com.example.payments.api.metrics;

import com.example.payments.api.coordination.ResponseCoordinator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * task_T9 (AUD-16): {@code paymentMethod} is now restricted by {@code @Pattern} at the request
 * boundary, but {@code api_requests_total} must stay bounded independently as a second line of
 * defense - the 51st distinct value seen (and every one after it) must collapse into a single
 * {@code "other"} series instead of minting a new Micrometer series per value.
 */
class ApiMetricsUnitTest {

    @Test
    void collapsesTheFiftyFirstDistinctPaymentMethodIntoTheOtherSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApiMetrics metrics = new ApiMetrics(registry, mock(ResponseCoordinator.class));
        metrics.init();

        for (int i = 1; i <= 50; i++) {
            metrics.recordRequest("METHOD_" + i);
        }
        metrics.recordRequest("METHOD_51");
        metrics.recordRequest("METHOD_52");

        // The first 50 distinct values each keep their own series.
        assertEquals(1.0, registry.get("api_requests_total")
                .tag("payment_method", "METHOD_1").counter().count());
        assertEquals(1.0, registry.get("api_requests_total")
                .tag("payment_method", "METHOD_50").counter().count());

        // The 51st and 52nd distinct values never mint their own series...
        assertNull(registry.find("api_requests_total").tag("payment_method", "METHOD_51").counter());
        assertNull(registry.find("api_requests_total").tag("payment_method", "METHOD_52").counter());

        // ...they collapse into the shared "other" series instead.
        assertEquals(2.0, registry.get("api_requests_total")
                .tag("payment_method", "other").counter().count());
    }

    @Test
    void aRepeatedValueWithinTheLimitKeepsItsOwnSeriesInsteadOfCollapsing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApiMetrics metrics = new ApiMetrics(registry, mock(ResponseCoordinator.class));
        metrics.init();

        metrics.recordRequest("CREDIT_CARD");
        metrics.recordRequest("CREDIT_CARD");
        metrics.recordRequest("CREDIT_CARD");

        assertEquals(3.0, registry.get("api_requests_total")
                .tag("payment_method", "CREDIT_CARD").counter().count());
        assertNull(registry.find("api_requests_total").tag("payment_method", "other").counter());
    }
}
