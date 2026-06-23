package com.example.payments.api.dto;

import com.example.payments.common.model.SimulationResult;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** Response body for GET /payment-simulations/{requestId} and accepted (202) responses. */
@Serdeable
public record StatusResponse(
        String requestId,
        SimulationStatus status,
        @Nullable String statusUrl,
        @Nullable SimulationResult result
) {
}
