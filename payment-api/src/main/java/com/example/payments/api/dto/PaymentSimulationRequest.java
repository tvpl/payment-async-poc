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
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Validated HTTP request body for POST /payment-simulations. */
@Serdeable
public record PaymentSimulationRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String merchantId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Pattern(regexp = "[A-Z_]{2,32}") String paymentMethod,
        @Nullable @Pattern(regexp = "[A-Z_]{2,32}") String brand,
        @NotNull @Min(1) @Max(24) Integer installments,
        @NotBlank @Pattern(regexp = "[A-Z_]{2,32}") String captureMode
) {

    public PaymentSimulationRequestPayload toPayload() {
        return new PaymentSimulationRequestPayload(
                merchantId, amount, currency, paymentMethod, brand, installments, captureMode);
    }
}
