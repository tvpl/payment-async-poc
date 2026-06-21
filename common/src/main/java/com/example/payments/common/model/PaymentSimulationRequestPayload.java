package com.example.payments.common.model;

import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;

/** Business payload describing the payment to simulate. */
@Serdeable
public record PaymentSimulationRequestPayload(
        String merchantId,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        String brand,
        Integer installments,
        String captureMode
) {
}
