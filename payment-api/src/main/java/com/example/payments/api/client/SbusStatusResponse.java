package com.example.payments.api.client;

import com.example.payments.common.model.SimulationResult;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** Mirror of the SBUS internal status view (durable fallback source). */
@Serdeable
public record SbusStatusResponse(
        String requestId,
        String status,
        @Nullable SimulationResult result
) {
}
