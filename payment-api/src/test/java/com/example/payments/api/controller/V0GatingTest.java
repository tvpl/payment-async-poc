package com.example.payments.api.controller;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.service.ApiPaymentService;
import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.kafka.TopicRouter;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import com.example.platform.featurecontrol.resolver.FeatureResolver;
import com.example.platform.featurecontrol.resolver.MasterSwitch;
import com.example.platform.featurecontrol.spi.FlagSource;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the v0 gate without booting the full context (which would need Kafka/Redis): the controller
 * is exercised directly with a mocked {@link ApiPaymentService} and a real {@link FeatureResolver}
 * seeded with the {@code payment-api-v0} allowlist. Confirms 404 for non-eligible callers, a real
 * result for the {@code v0-testers} group, and the {@code X-Api-Version} header — the two guarantees
 * that make v0 safe to expose in production.
 */
class V0GatingTest {

    private static final FlagDefinition V0 = new FlagDefinition(
            "payment-api-v0", FlagType.ALLOWLIST, true, 0, null,
            java.util.Set.of("v0-testers"), null, "v0", "v1");

    private final FlagSource flags = name -> "payment-api-v0".equals(name) ? Optional.of(V0) : Optional.empty();
    private final FeatureResolver resolver =
            new FeatureResolver(flags, new MasterSwitch(new FeatureSettings(), flags), List.of());
    private final TopicRouter topicRouter = new TopicRouter(resolver);

    private final PaymentSimulationRequest request = new PaymentSimulationRequest(
            "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");

    private V0PaymentSimulationController controller(ApiPaymentService service) {
        return new V0PaymentSimulationController(service, resolver, topicRouter);
    }

    private Authentication user(String name, String... groups) {
        return Authentication.build(name, List.of(groups));
    }

    @Test
    void hidesV0FromNonEligibleCallers() {
        ApiPaymentService service = mock(ApiPaymentService.class);
        HttpResponse<?> response = controller(service).create(user("carol"), request, "");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
    }

    @Test
    void anonymousGetsNotFound() {
        ApiPaymentService service = mock(ApiPaymentService.class);
        HttpResponse<?> response = controller(service).create(null, request, "");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
    }

    @Test
    void eligibleCallerReachesPipelineWithV0Header() {
        ApiPaymentService service = mock(ApiPaymentService.class);
        StatusEntry entry = new StatusEntry("req-1", SimulationStatus.COMPLETED, null);
        when(service.submit(any(), anyString())).thenReturn(new ApiPaymentService.SubmitResult(entry, false, false));

        HttpResponse<?> response = controller(service).create(user("bob", "v0-testers"), request, "");

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals("v0", response.getHeaders().get("X-Api-Version"));
    }
}
