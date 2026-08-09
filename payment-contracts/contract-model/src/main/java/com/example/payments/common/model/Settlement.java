package com.example.payments.common.model;

import java.time.LocalDate;

/** Settlement projection for a simulated payment. */
public record Settlement(
        LocalDate settlementDate,
        String settlementType
) {
}
