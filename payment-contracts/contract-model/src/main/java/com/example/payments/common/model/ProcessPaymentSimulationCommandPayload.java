package com.example.payments.common.model;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Command emitted by the SBUS (via the outbox) toward the Core. Carries the
 * SBUS-assigned {@code simulationId} plus the original request payload.
 */
@Serdeable
public record ProcessPaymentSimulationCommandPayload(
        String simulationId,
        PaymentSimulationRequestPayload request
) {
}
