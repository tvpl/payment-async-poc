package com.example.payments.api.controller;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.dto.StatusResponse;
import com.example.payments.api.service.ApiPaymentService;
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

    private final ApiPaymentService service;

    public PaymentSimulationController(ApiPaymentService service) {
        this.service = service;
    }

    @Post
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<StatusResponse> create(
            @Valid @Body PaymentSimulationRequest request,
            @Header(name = Headers.IDEMPOTENCY_KEY, defaultValue = "") @Nullable String idempotencyKey) {

        ApiPaymentService.SubmitResult result = service.submit(request, idempotencyKey);
        StatusEntry entry = result.entry();
        String statusUrl = statusUrl(entry.requestId());
        StatusResponse body = new StatusResponse(
                entry.requestId(), entry.status(), statusUrl, entry.result());

        if (!result.timedOut()) {
            if (entry.status() == SimulationStatus.COMPLETED) {
                return HttpResponse.ok(body);                       // 200
            }
            if (entry.status() == SimulationStatus.FAILED) {
                return HttpResponse.<StatusResponse>status(HttpStatus.UNPROCESSABLE_ENTITY).body(body); // 422
            }
        }
        // Still processing — client polls statusUrl or waits for an out-of-band result.
        return HttpResponse.accepted().body(body);                  // 202
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
