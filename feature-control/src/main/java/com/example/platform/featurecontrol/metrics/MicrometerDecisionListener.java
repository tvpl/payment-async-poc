package com.example.platform.featurecontrol.metrics;

import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.model.FeatureDecision;
import com.example.platform.featurecontrol.spi.DecisionListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * Emits Prometheus metrics for feature decisions so a roll-out is observable in Grafana:
 * {@code feature_decisions_total{flag,variant,on,reason_kind}}. Only wired when a {@link MeterRegistry}
 * exists (an app with micronaut-micrometer); apps without metrics simply don't get this listener.
 *
 * <p>The {@code reason} is reduced to its <em>kind</em> (the part before {@code :}) to keep tag
 * cardinality bounded — {@code percentage:bucket=37<40->on} would otherwise explode the label space.
 */
@Singleton
@Requires(beans = MeterRegistry.class)
public class MicrometerDecisionListener implements DecisionListener {

    private final MeterRegistry registry;

    public MicrometerDecisionListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onDecision(String flag, FeatureDecision decision, FeatureContext context) {
        registry.counter("feature_decisions_total",
                "flag", flag,
                "variant", decision.variant(),
                "on", Boolean.toString(decision.isOn()),
                "reason_kind", reasonKind(decision.reason())).increment();
    }

    private static String reasonKind(String reason) {
        if (reason == null) {
            return "unknown";
        }
        int colon = reason.indexOf(':');
        return colon < 0 ? reason : reason.substring(0, colon);
    }
}
