package com.example.payments.common.model;

import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;

/** Fee breakdown for a simulated payment. */
@Serdeable
public record Fees(
        BigDecimal mdr,
        BigDecimal interchange,
        BigDecimal netAmount
) {
}
