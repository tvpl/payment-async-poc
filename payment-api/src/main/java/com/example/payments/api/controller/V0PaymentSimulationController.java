package com.example.payments.api.controller;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusResponse;
import com.example.payments.api.error.Problem;
import com.example.payments.api.service.ApiPaymentService;
import com.example.payments.common.events.Headers;
import com.example.payments.common.model.SimulationStatus;
import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.context.JwtFeatureContextFactory;
import com.example.platform.featurecontrol.model.FeatureDecision;
import com.example.platform.featurecontrol.resolver.FeatureResolver;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import jakarta.validation.Valid;

/**
 * The <strong>v0 (beta) API</strong>, gated to a restricted group via the {@code feature-control} lib.
 * Only callers whose JWT is eligible for the {@code payment-api-v0} flag (the {@code v0-testers}
 * group) may use it; everyone else gets 404, so v0 is invisible to the general population. This is
 * how you ship a test version to production for a select audience.
 *
 * <p>It is deliberately <strong>additive</strong>: eligible requests reuse the exact, already-tested
 * {@link ApiPaymentService} pipeline. v0 only adds gating + response headers ({@code X-Api-Version},
 * {@code X-Feature-Reason}). The path is not covered by {@code ApiKeyFilter} (which only matches
 * {@code /payment-simulations**}); its auth gate is the JWT + feature flag instead — deliberately,
 * since v0 is a beta offered to a named JWT-eligible group, not a credentialed integration. It IS
 * covered by {@code ConcurrencyLimitFilter}, though: an anonymous, unauthenticated POST route is
 * exactly the kind of endpoint that must not be exempt from admission control (CAP-03).
 */
@Controller("/v0/payment-simulations")
@Secured(SecurityRule.IS_ANONYMOUS)
public class V0PaymentSimulationController {

    private static final String V0_FLAG = "payment-api-v0";
    /**
     * v0 is anonymous by design (no {@code X-API-Key}, see the class javadoc and
     * {@code ConcurrencyLimitFilter}'s identical convention) so it never resolves a tenant from a
     * binding; every v0 caller shares this fixed scope for idempotency/envelope purposes.
     */
    private static final String V0_TENANT = "anonymous";

    private final ApiPaymentService service;
    private final FeatureResolver features;

    public V0PaymentSimulationController(ApiPaymentService service,
                                         FeatureResolver features) {
        this.service = service;
        this.features = features;
    }

    @Post
    @ExecuteOn(TaskExecutors.BLOCKING)
    public MutableHttpResponse<?> create(
            @Nullable Authentication authentication,
            @Valid @Body PaymentSimulationRequest request,
            @Header(name = Headers.IDEMPOTENCY_KEY, defaultValue = "") @Nullable String idempotencyKey,
            @Header(name = Headers.CORRELATION_ID, defaultValue = "") @Nullable String correlationIdHeader) {

        FeatureContext ctx = JwtFeatureContextFactory.from(authentication, null);
        FeatureDecision v0 = features.evaluate(V0_FLAG, ctx);
        if (!v0.isOn()) {
            // Hide the beta from non-eligible callers.
            return HttpResponse.status(HttpStatus.NOT_FOUND)
                    .contentType(Problem.MEDIA_TYPE)
                    .body(Problem.of(404, "Not Found", "No such resource"));
        }

        if (!IdempotencyKeyValidation.isValid(idempotencyKey)) {
            return HttpResponse.status(HttpStatus.BAD_REQUEST)
                    .contentType(Problem.MEDIA_TYPE)
                    .body(Problem.of(400, "Invalid request",
                            "Idempotency-Key header is required and must match [A-Za-z0-9_-]{1,128}"));
        }

        ApiPaymentService.SubmitResult result = service.submit(request, idempotencyKey, V0_TENANT, correlationIdHeader);
        var entry = result.entry();
        StatusResponse body = new StatusResponse(
                entry.requestId(), entry.status(), statusUrl(entry.requestId()), entry.result());

        HttpStatus status = HttpStatus.ACCEPTED;
        if (!result.timedOut()) {
            if (entry.status() == SimulationStatus.COMPLETED) {
                status = HttpStatus.OK;
            } else if (entry.status() == SimulationStatus.FAILED) {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
            }
        }
        return HttpResponse.status(status).body(body)
                .header("X-Api-Version", "v0")
                .header("X-Feature-Reason", v0.reason())
                .header(Headers.CORRELATION_ID, result.correlationId());
    }

    @Get("/{requestId}")
    public MutableHttpResponse<?> get(@Nullable Authentication authentication,
                                      @PathVariable String requestId) {
        FeatureContext ctx = JwtFeatureContextFactory.from(authentication, null);
        if (!features.isEnabled(V0_FLAG, ctx)) {
            return HttpResponse.status(HttpStatus.NOT_FOUND)
                    .contentType(Problem.MEDIA_TYPE)
                    .body(Problem.of(404, "Not Found", "No such resource"));
        }
        return service.getStatus(requestId)
                .<MutableHttpResponse<?>>map(entry -> HttpResponse.ok(new StatusResponse(
                        entry.requestId(), entry.status(), statusUrl(requestId), entry.result()))
                        .header("X-Api-Version", "v0"))
                .orElseGet(HttpResponse::notFound);
    }

    private static String statusUrl(String requestId) {
        return "/v0/payment-simulations/" + requestId;
    }
}
