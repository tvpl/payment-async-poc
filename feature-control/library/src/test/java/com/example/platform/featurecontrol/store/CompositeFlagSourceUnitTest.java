package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FlagDefinitionProperties;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeFlagSourceUnitTest {

    private StaticFlagSource baselineWith(String name, boolean enabled) {
        FlagDefinitionProperties p = new FlagDefinitionProperties(name);
        p.setType(FlagType.BOOLEAN);
        p.setEnabled(enabled);
        return new StaticFlagSource(List.of(p));
    }

    /** A stand-in dynamic source; constructor args are unused because find() is overridden. */
    private RedisFlagSource dynamicReturning(Optional<FlagDefinition> value) {
        return new RedisFlagSource(null, null, null) {
            @Override
            public Optional<FlagDefinition> find(String name) {
                return value;
            }
        };
    }

    @Test
    void redisOverrideBeatsBaseline() {
        StaticFlagSource baseline = baselineWith("f", false);
        FlagDefinition override = new FlagDefinition(
                "f", FlagType.BOOLEAN, true, 0, null, null, null, "on", "off");
        CompositeFlagSource composite =
                new CompositeFlagSource(baseline, dynamicReturning(Optional.of(override)));

        assertTrue(composite.find("f").orElseThrow().enabled(), "dynamic override should win");
    }

    @Test
    void fallsBackToBaselineWhenDynamicEmpty() {
        StaticFlagSource baseline = baselineWith("f", true);
        CompositeFlagSource composite =
                new CompositeFlagSource(baseline, dynamicReturning(Optional.empty()));

        assertTrue(composite.find("f").orElseThrow().enabled(), "baseline should apply");
        assertTrue(composite.find("missing").isEmpty());
    }

    @Test
    void baselineOnlyWhenNoDynamicSource() {
        StaticFlagSource baseline = baselineWith("f", false);
        CompositeFlagSource composite = new CompositeFlagSource(baseline, null);

        assertFalse(composite.find("f").orElseThrow().enabled());
    }
}
