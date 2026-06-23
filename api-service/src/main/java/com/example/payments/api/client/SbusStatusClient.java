package com.example.payments.api.client;

import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.annotation.Client;

import java.util.Optional;

/**
 * Calls the SBUS durable status endpoint. Used as a fallback when the API's Redis
 * entry is missing or not yet terminal, so a finished result is never lost.
 */
@Client("${sbus.base-url:`http://localhost:8081`}")
public interface SbusStatusClient {

    @Get("/internal/payment-simulations/{requestId}")
    Optional<SbusStatusResponse> getStatus(String requestId);
}
