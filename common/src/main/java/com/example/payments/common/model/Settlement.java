package com.example.payments.common.model;

import io.micronaut.serde.annotation.Serdeable;

import java.time.LocalDate;

/** Settlement projection for a simulated payment. */
@Serdeable
public record Settlement(
        LocalDate settlementDate,
        String settlementType
) {
}
