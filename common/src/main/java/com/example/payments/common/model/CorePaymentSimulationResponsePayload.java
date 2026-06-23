package com.example.payments.common.model;

import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;

/** Response produced by the (simulated) Core after processing a command. */
@Serdeable
public record CorePaymentSimulationResponsePayload(
        String simulationId,
        String status,
        String authorizationCode,
        BigDecimal amount,
        String currency,
        Integer installments,
        Fees fees,
        Settlement settlement,
        String errorCode,
        String errorMessage
) {
}
