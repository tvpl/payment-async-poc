package com.example.payments.sbus.controller;

import com.example.payments.common.model.SimulationResult;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** Durable status projection returned by the SBUS internal status endpoint. */
@Serdeable
public record SbusStatusView(
        String requestId,
        String status,
        @Nullable SimulationResult result
) {
}
