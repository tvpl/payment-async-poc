package com.example.payments.common.model;

import java.math.BigDecimal;

/** Business payload describing the payment to simulate. */
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
