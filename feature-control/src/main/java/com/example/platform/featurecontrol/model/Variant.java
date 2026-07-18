package com.example.platform.featurecontrol.model;

import io.micronaut.serde.annotation.Serdeable;

/**
 * A named variant with a relative weight, used by {@link FlagType#VARIANT} flags.
 * Weights need not sum to 100 — they are normalized during weighted selection.
 */
@Serdeable
public record Variant(String name, int weight) {

    public Variant {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variant name is required");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("variant weight must be >= 0");
        }
    }
}
