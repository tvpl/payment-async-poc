package com.example.platform.featurecontrol.model;

import io.micronaut.serde.annotation.Serdeable;

/**
 * The outcome of evaluating a flag for a given context: the chosen {@code variant}, whether it
 * counts as "on", and a human-readable {@code reason} that makes decisions auditable/debuggable
 * (e.g. {@code allowlist:user}, {@code percentage:bucket=37<40->on}, {@code toggle:on},
 * {@code default:off}). The {@code reason} is meant for logs, response headers, and dashboards.
 */
@Serdeable
public record FeatureDecision(String flag, String variant, boolean on, String reason) {

    public boolean isOn() {
        return on;
    }

    public boolean is(String variantName) {
        return variant != null && variant.equals(variantName);
    }
}
