package com.example.platform.featurecontrol.source;

import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTR-02: "IF Redis ou pub/sub falhar THEN the resolver SHALL aplicar uma política documentada de
 * last-known-good, baseline ou fail-closed com idade máxima observável." Exercises every combination
 * of {@code StalePolicy.apply} directly — no Redis involved, so every branch is deterministic.
 */
class StalePolicyUnitTest {

    private static final FlagDefinition LKG = new FlagDefinition(
            "f", FlagType.BOOLEAN, true, 0, null, null, null, "on", "off");

    @Test
    void withinMaxStaleServesLastKnownGoodRegardlessOfFallback() {
        Optional<FlagDefinition> baseline = StalePolicy.apply("f", LKG, 1_000, 5_000, StaleFallback.BASELINE);
        Optional<FlagDefinition> failClosed = StalePolicy.apply("f", LKG, 1_000, 5_000, StaleFallback.FAIL_CLOSED);

        assertEquals(LKG, baseline.orElseThrow());
        assertEquals(LKG, failClosed.orElseThrow());
    }

    @Test
    void atExactlyMaxStaleStillServesLastKnownGood() {
        // Boundary: age == max-stale is still "within budget" (<=), not yet stale.
        Optional<FlagDefinition> result = StalePolicy.apply("f", LKG, 5_000, 5_000, StaleFallback.BASELINE);
        assertEquals(LKG, result.orElseThrow());
    }

    @Test
    void oneMillisecondBeyondMaxStaleWithBaselinePolicyDefersToBaseline() {
        Optional<FlagDefinition> result = StalePolicy.apply("f", LKG, 5_001, 5_000, StaleFallback.BASELINE);
        assertTrue(result.isEmpty(), "beyond max-stale with BASELINE must defer (empty), not keep serving LKG");
    }

    @Test
    void beyondMaxStaleWithFailClosedForcesOff() {
        Optional<FlagDefinition> result = StalePolicy.apply("f", LKG, 10_000, 5_000, StaleFallback.FAIL_CLOSED);
        FlagDefinition forced = result.orElseThrow();

        assertFalse(forced.enabled(), "FAIL_CLOSED must force the flag off");
        assertEquals("f", forced.name());
    }

    @Test
    void beyondMaxStaleWithFailClosedPreservesTheOffVariantLabel() {
        FlagDefinition lkgWithCustomOff = new FlagDefinition(
                "f", FlagType.BOOLEAN, true, 0, null, null, null, "service-b", "service-a");
        FlagDefinition forced = StalePolicy.apply(
                "f", lkgWithCustomOff, 10_000, 5_000, StaleFallback.FAIL_CLOSED).orElseThrow();

        assertEquals("service-a", forced.offVariant(), "forced-off should keep the known off label, not a generic one");
    }

    @Test
    void neverFetchedWithBaselinePolicyDefersToBaseline() {
        Optional<FlagDefinition> result = StalePolicy.apply(
                "f", null, Long.MAX_VALUE, 5_000, StaleFallback.BASELINE);
        assertTrue(result.isEmpty());
    }

    @Test
    void neverFetchedWithFailClosedStillForcesOff() {
        Optional<FlagDefinition> result = StalePolicy.apply(
                "never-seen", null, Long.MAX_VALUE, 5_000, StaleFallback.FAIL_CLOSED);
        FlagDefinition forced = result.orElseThrow();

        assertFalse(forced.enabled());
        assertEquals("never-seen", forced.name());
    }

    @Test
    void forcedOffDefinitionIsAlwaysBooleanEvenForAVariantFlag() {
        // A VARIANT/PERCENTAGE LKG can't be "forced off" by emptying its variants (FlagDefinition's
        // own FTR-01 validation rejects an empty VARIANT list) — StalePolicy must fall back to a
        // plain BOOLEAN off, which FeatureResolver's enabled()==false short-circuit already handles
        // for every type.
        FlagDefinition variantLkg = new FlagDefinition("v", FlagType.VARIANT, true, 0, null, null,
                java.util.List.of(new com.example.platform.featurecontrol.model.Variant("a", 1)), "on", "off");

        FlagDefinition forced = StalePolicy.apply(
                "v", variantLkg, 10_000, 5_000, StaleFallback.FAIL_CLOSED).orElseThrow();

        assertEquals(FlagType.BOOLEAN, forced.type());
        assertFalse(forced.enabled());
    }
}
