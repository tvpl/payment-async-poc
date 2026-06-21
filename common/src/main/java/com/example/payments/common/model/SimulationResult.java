package com.example.payments.common.model;

import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;

/**
 * Final result of a payment simulation, returned to the HTTP client and carried
 * by the {@code PaymentSimulationCompleted}/{@code Failed} events.
 *
 * @param status APPROVED / DECLINED / ERROR (business outcome from the Core)
 */
@Serdeable
public record SimulationResult(
        String simulationId,
        String requestId,
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
    public static final String APPROVED = "APPROVED";
    public static final String DECLINED = "DECLINED";
    public static final String ERROR = "ERROR";
}
