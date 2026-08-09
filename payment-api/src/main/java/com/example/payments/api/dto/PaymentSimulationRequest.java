package com.example.payments.api.dto;

import com.example.payments.common.model.PaymentSimulationRequestPayload;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** Validated HTTP request body for POST /payment-simulations. */
@Serdeable
public record PaymentSimulationRequest(
        @NotBlank String merchantId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank String paymentMethod,
        @Nullable String brand,
        @NotNull @Min(1) @Max(24) Integer installments,
        @NotBlank String captureMode
) {

    public PaymentSimulationRequestPayload toPayload() {
        return new PaymentSimulationRequestPayload(
                merchantId, amount, currency, paymentMethod, brand, installments, captureMode);
    }
}
