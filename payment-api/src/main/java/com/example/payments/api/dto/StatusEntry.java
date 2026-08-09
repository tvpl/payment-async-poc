package com.example.payments.api.dto;

import com.example.payments.common.model.SimulationResult;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** What we persist in Redis under {@code payment-simulation:{requestId}}. */
@Serdeable
public record StatusEntry(
        String requestId,
        SimulationStatus status,
        @Nullable SimulationResult result
) {
}
