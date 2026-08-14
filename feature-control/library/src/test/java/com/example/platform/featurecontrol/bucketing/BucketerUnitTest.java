package com.example.platform.featurecontrol.bucketing;

import com.example.platform.featurecontrol.model.Variant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BucketerUnitTest {

    @Test
    void bucketIsDeterministicAndSticky() {
        // Same salt/key must always land in the same bucket — that is what makes A/B sticky.
        int first = Bucketer.bucket("checkout", "user-42");
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, Bucketer.bucket("checkout", "user-42"));
        }
    }

    @Test
    void bucketIsWithinRange() {
        for (int i = 0; i < 10_000; i++) {
            int b = Bucketer.bucket("flag", UUID.randomUUID().toString());
            assertTrue(b >= 0 && b < 100, "bucket out of range: " + b);
        }
    }

    @Test
    void differentSaltsDecorrelate() {
        // A user unlucky (or lucky) on one flag should not be pinned to the same bucket on another.
        int sameBoth = 0;
        for (int i = 0; i < 5000; i++) {
            String key = "user-" + i;
            if (Bucketer.bucket("flag-a", key) == Bucketer.bucket("flag-b", key)) {
                sameBoth++;
            }
        }
        // With independent buckets, collisions should be ~1% (1/100), well under 5%.
        assertTrue(sameBoth < 250, "salts not decorrelated, collisions=" + sameBoth);
    }

    @Test
    void distributionIsApproximatelyUniform() {
        int belowTen = 0;
        int total = 100_000;
        for (int i = 0; i < total; i++) {
            if (Bucketer.bucket("rollout", "user-" + i) < 10) {
                belowTen++;
            }
        }
        double pct = (belowTen * 100.0) / total;
        // Target 10%; allow a generous tolerance for the statistical test.
        assertTrue(pct > 8.5 && pct < 11.5, "distribution off: " + pct + "%");
    }

    @Test
    void weightedSelectRespectsWeightsAndIsDeterministic() {
        List<Variant> variants = List.of(new Variant("a", 90), new Variant("b", 10));
        Variant first = Bucketer.select(variants, "exp", "user-7");
        assertNotNull(first);
        assertEquals(first.name(), Bucketer.select(variants, "exp", "user-7").name());

        int bCount = 0;
        int total = 20_000;
        for (int i = 0; i < total; i++) {
            if ("b".equals(Bucketer.select(variants, "exp", "user-" + i).name())) {
                bCount++;
            }
        }
        double pct = (bCount * 100.0) / total;
        assertTrue(pct > 8.0 && pct < 12.0, "weighted split off: " + pct + "%");
    }

    @Test
    void selectHandlesEmptyAndZeroWeights() {
        assertNull(Bucketer.select(List.of(), "x", "y"));
        // AUD-22: all-zero weights must resolve off (null) -- there is no valid distribution to pick
        // from, and a weight-0 variant must never be selectable per this method's own contract.
        List<Variant> zero = List.of(new Variant("only", 0));
        assertNull(Bucketer.select(zero, "x", "y"));
    }

    @Test
    void selectWithAllZeroWeightsAcrossMultipleVariantsStillResolvesOff() {
        // Same defect with more than one candidate: none of them has a positive weight, so none is a
        // valid pick -- not "silently fall back to whichever is first in the list".
        List<Variant> allZero = List.of(new Variant("a", 0), new Variant("b", 0));
        assertNull(Bucketer.select(allZero, "x", "y"));
    }
}
