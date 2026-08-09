package com.example.payments.common.model;

import java.math.BigDecimal;

/** Fee breakdown for a simulated payment. */
public record Fees(
        BigDecimal mdr,
        BigDecimal interchange,
        BigDecimal netAmount
) {
}
