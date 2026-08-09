package com.example.payments.sbus.controller;

import com.example.payments.common.model.SimulationResult;
import com.example.payments.sbus.domain.PaymentSbusMessage;
import com.example.payments.sbus.domain.SbusMessageStatus;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import com.example.payments.sbus.support.Json;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;

/**
 * Internal (service-to-service) durable status lookup. The API calls this as a
 * fallback when its Redis entry is missing or not yet terminal, so a result is
 * never lost just because Redis expired or an instance missed the final event.
 */
@Controller("/internal/payment-simulations")
public class InternalStatusController {

    private final PaymentSbusMessageRepository repository;
    private final Json json;

    public InternalStatusController(PaymentSbusMessageRepository repository, Json json) {
        this.repository = repository;
        this.json = json;
    }

    @Get("/{requestId}")
    public HttpResponse<SbusStatusView> get(@PathVariable String requestId) {
        return repository.findByRequestId(requestId)
                .map(this::toView)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    private SbusStatusView toView(PaymentSbusMessage m) {
        SimulationResult result = m.getResult() == null
                ? null
                : json.fromJson(m.getResult(), SimulationResult.class);
        return new SbusStatusView(m.getRequestId(), map(m.getStatus()).name(), result);
    }

    /** SBUS internal status -> API-facing lifecycle status. */
    private static com.example.payments.common.model.SimulationStatus map(SbusMessageStatus s) {
        return switch (s) {
            case COMPLETED -> com.example.payments.common.model.SimulationStatus.COMPLETED;
            case FAILED -> com.example.payments.common.model.SimulationStatus.FAILED;
            default -> com.example.payments.common.model.SimulationStatus.PROCESSING;
        };
    }
}
