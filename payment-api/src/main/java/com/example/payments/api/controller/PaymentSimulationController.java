package com.example.payments.api.controller;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.dto.StatusResponse;
import com.example.payments.api.error.Problem;
import com.example.payments.api.service.ApiPaymentService;
import com.example.payments.api.tenant.TenantResolution;
import com.example.payments.api.tenant.TenantResolver;
import com.example.payments.common.events.Headers;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;

/**
 * HTTP entry point for payment simulations.
 *
 * <p>The POST handler runs on {@link TaskExecutors#BLOCKING} which, on a Loom-capable
 * JDK, is backed by <strong>virtual threads</strong>. The request thread blocks
 * cheaply while the simulation is processed asynchronously; thousands can wait
 * concurrently without exhausting platform threads.
 */
@Controller("/payment-simulations")
public class PaymentSimulationController {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiPaymentService service;
    private final TenantResolver tenantResolver;

    public PaymentSimulationController(ApiPaymentService service, TenantResolver tenantResolver) {
        this.service = service;
        this.tenantResolver = tenantResolver;
    }

    @Post
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> create(
            @Valid @Body PaymentSimulationRequest request,
            @Header(name = Headers.IDEMPOTENCY_KEY, defaultValue = "") @Nullable String idempotencyKey,
            @Header(name = API_KEY_HEADER, defaultValue = "") @Nullable String apiKey,
            @Header(name = Headers.TENANT_ID, defaultValue = "") @Nullable String tenantIdHeader,
            @Header(name = Headers.CORRELATION_ID, defaultValue = "") @Nullable String correlationIdHeader) {

        TenantResolution resolution = tenantResolver.resolve(apiKey, tenantIdHeader);
        if (resolution instanceof TenantResolution.Forbidden) {
            return HttpResponse.status(HttpStatus.FORBIDDEN)
                    .contentType(Problem.MEDIA_TYPE)
                    .body(Problem.of(403, "Forbidden", "X-Tenant-Id is not authorized for this credential"));
        }
        if (resolution instanceof TenantResolution.MissingHeader) {
            return HttpResponse.status(HttpStatus.BAD_REQUEST)
                    .contentType(Problem.MEDIA_TYPE)
                    .body(Problem.of(400, "Invalid request",
                            "X-Tenant-Id header is required for this credential"));
        }

        if (!IdempotencyKeyValidation.isValid(idempotencyKey)) {
            return HttpResponse.status(HttpStatus.BAD_REQUEST)
                    .contentType(Problem.MEDIA_TYPE)
                    .body(Problem.of(400, "Invalid request",
                            "Idempotency-Key header is required and must match [A-Za-z0-9_-]{1,128}"));
        }

        String tenantId = ((TenantResolution.Effective) resolution).tenantId();
        ApiPaymentService.SubmitResult result = service.submit(request, idempotencyKey, tenantId, correlationIdHeader);
        StatusEntry entry = result.entry();
        String statusUrl = statusUrl(entry.requestId());
        StatusResponse body = new StatusResponse(
                entry.requestId(), entry.status(), statusUrl, entry.result());

        if (!result.timedOut()) {
            if (entry.status() == SimulationStatus.COMPLETED) {
                return HttpResponse.ok(body)                        // 200
                        .header(Headers.CORRELATION_ID, result.correlationId());
            }
            if (entry.status() == SimulationStatus.FAILED) {
                return HttpResponse.<StatusResponse>status(HttpStatus.UNPROCESSABLE_ENTITY).body(body) // 422
                        .header(Headers.CORRELATION_ID, result.correlationId());
            }
        }
        // Still processing — client polls statusUrl or waits for an out-of-band result.
        return HttpResponse.accepted().body(body)                   // 202
                .header(Headers.CORRELATION_ID, result.correlationId());
    }

    @Get("/{requestId}")
    public HttpResponse<StatusResponse> get(@PathVariable String requestId) {
        return service.getStatus(requestId)
                .map(entry -> HttpResponse.ok(new StatusResponse(
                        entry.requestId(), entry.status(), statusUrl(requestId), entry.result())))
                .orElseGet(HttpResponse::notFound);
    }

    private static String statusUrl(String requestId) {
        return "/payment-simulations/" + requestId;
    }
}
