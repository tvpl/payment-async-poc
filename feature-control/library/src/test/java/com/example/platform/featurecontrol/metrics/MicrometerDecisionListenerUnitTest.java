package com.example.platform.featurecontrol.metrics;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.model.FeatureDecision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FTR-05: "alta cardinalidade sintética permanece bounded" — asserted against the real meter registry. */
class MicrometerDecisionListenerUnitTest {

    private static FeatureSettings settingsWithLimit(int limit) {
        FeatureSettings settings = new FeatureSettings();
        settings.setMetricCardinalityLimit(limit);
        return settings;
    }

    @Test
    void aFewDistinctFlagsEachGetTheirOwnTaggedSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerDecisionListener listener = new MicrometerDecisionListener(registry, settingsWithLimit(200));
        FeatureContext ctx = FeatureContext.anonymous("anon-1");

        listener.onDecision("flag-a", new FeatureDecision("flag-a", "on", true, "toggle:on"), ctx);
        listener.onDecision("flag-b", new FeatureDecision("flag-b", "on", true, "toggle:on"), ctx);

        assertEquals(1.0, registry.counter("feature_decisions_total",
                "flag", "flag-a", "variant", "on", "on", "true", "reason_kind", "toggle").count());
        assertEquals(1.0, registry.counter("feature_decisions_total",
                "flag", "flag-b", "variant", "on", "on", "true", "reason_kind", "toggle").count());
        assertEquals(2, registry.getMeters().size());
    }

    @Test
    void syntheticHighCardinalityFlagNamesCollapseToABoundedSeriesCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerDecisionListener listener = new MicrometerDecisionListener(registry, settingsWithLimit(50));
        FeatureContext ctx = FeatureContext.anonymous("anon-1");

        for (int i = 0; i < 5_000; i++) {
            listener.onDecision("synthetic-flag-" + i,
                    new FeatureDecision("synthetic-flag-" + i, "on", true, "toggle:on"), ctx);
        }

        // At most: 50 admitted distinct flag names + 1 "other" overflow series for everything beyond.
        assertTrue(registry.getMeters().size() <= 51,
                "5,000 synthetic flag names must collapse to a bounded series count, was " + registry.getMeters().size());
        assertEquals(4_950.0, registry.counter("feature_decisions_total",
                "flag", CardinalityGuard.OVERFLOW, "variant", "on", "on", "true", "reason_kind", "toggle").count(),
                "every flag name beyond the limit must accumulate on the shared overflow series");
    }

    @Test
    void syntheticHighCardinalityVariantNamesCollapseIndependentlyOfFlagNames() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerDecisionListener listener = new MicrometerDecisionListener(registry, settingsWithLimit(10));
        FeatureContext ctx = FeatureContext.anonymous("anon-1");

        for (int i = 0; i < 100; i++) {
            listener.onDecision("stable-flag",
                    new FeatureDecision("stable-flag", "variant-" + i, true, "variant:variant-" + i), ctx);
        }

        // "flag" alone never overflows (one stable name), but "variant" must — the two guards are independent.
        assertTrue(registry.getMeters().size() <= 11,
                "100 synthetic variant names on one stable flag must still collapse via the variant guard, was "
                        + registry.getMeters().size());
    }
}
