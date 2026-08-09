package com.example.payments.api.client;

import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.client.annotation.Client;

import java.util.Optional;

/**
 * Calls the SBUS durable status endpoint. Used as a fallback when the API's Redis
 * entry is missing or not yet terminal, so a finished result is never lost.
 *
 * <p>Declared against the {@code sbus} service id so its connect/read budget is
 * configured in one place ({@code micronaut.http.services.sbus}) and every call carries
 * the caller's service identity (PAY-09).
 */
@Client(id = "sbus")
public interface SbusStatusClient {

    @Get("/internal/payment-simulations/{requestId}")
    Optional<SbusStatusResponse> getStatus(String requestId,
                                           @Header("X-Service-Name") String serviceName);
}
