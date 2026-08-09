package com.example.payments.common.model;

/**
 * Command emitted by the SBUS (via the outbox) toward the Core. Carries the
 * SBUS-assigned {@code simulationId} plus the original request payload.
 */
public record ProcessPaymentSimulationCommandPayload(
        String simulationId,
        PaymentSimulationRequestPayload request
) {
}
