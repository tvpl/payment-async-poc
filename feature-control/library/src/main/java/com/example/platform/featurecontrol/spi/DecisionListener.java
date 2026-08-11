package com.example.platform.featurecontrol.spi;

import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.model.FeatureDecision;

/**
 * Observer notified on every resolved decision. This is the extension point for telemetry (metrics,
 * exposure logs, experiment events) that keeps the resolver itself free of any hard dependency on
 * Micrometer or a logging convention. Implementations must be cheap and must never throw — the
 * resolver isolates them, but a slow listener would still be on the hot path.
 */
public interface DecisionListener {

    void onDecision(String flag, FeatureDecision decision, FeatureContext context);
}
