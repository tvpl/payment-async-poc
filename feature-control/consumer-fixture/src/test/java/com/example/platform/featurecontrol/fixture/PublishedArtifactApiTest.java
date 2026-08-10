package com.example.platform.featurecontrol.fixture;

import com.example.platform.featurecontrol.bucketing.Bucketer;
import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.model.FeatureDecision;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import com.example.platform.featurecontrol.model.Variant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the feature-control public API using <strong>only</strong> the published
 * {@code com.example.platform:feature-control:0.1.0} GAV (see {@code build.gradle}'s
 * {@code exclusiveContent} — no {@code project(...)} source substitution is possible here). If a
 * released version drops or breaks any of these symbols, this fixture fails to compile or run
 * against it, exactly what the ORG-04/ORG-05/FTR-06 "published contract" guarantee requires.
 */
class PublishedArtifactApiTest {

    @Test
    void flagDefinitionValidatesAndExposesItsFields() {
        FlagDefinition definition = new FlagDefinition(
                "checkout-v2", FlagType.PERCENTAGE, true, 25,
                Set.of("alice"), Set.of("beta-testers"), null, "on", "off", 3L, null);

        assertEquals("checkout-v2", definition.name());
        assertEquals(FlagType.PERCENTAGE, definition.type());
        assertEquals(25, definition.percentage());
        assertEquals(3L, definition.version());
        assertEquals("on", definition.onName());
        assertEquals("checkout-v2", definition.effectiveSalt(), "salt defaults to the flag name");
    }

    @Test
    void bucketingIsDeterministicForTheSameSaltAndKey() {
        int first = Bucketer.bucket("checkout-v2", "user-42");
        int second = Bucketer.bucket("checkout-v2", "user-42");

        assertEquals(first, second, "the same salt/key pair must always land in the same bucket");
        assertTrue(first >= 0 && first < 100, "bucket must be in [0,100)");
    }

    @Test
    void variantSelectionIsDeterministicAndRespectsZeroWeight() {
        List<Variant> variants = List.of(new Variant("a", 1), new Variant("b", 0));

        Variant chosen = Bucketer.select(variants, "flag", "user-1");

        assertNotNull(chosen);
        assertEquals("a", chosen.name(), "the only positive-weight variant must always be chosen");
    }

    @Test
    void featureContextBucketingKeyFallsBackToAnonymousId() {
        FeatureContext withUser = FeatureContext.builder().userId("bob").build();
        FeatureContext anon = FeatureContext.anonymous("device-99");

        assertEquals("bob", withUser.bucketingKey());
        assertEquals("device-99", anon.bucketingKey());
        assertTrue(anon.groups().isEmpty());
    }

    @Test
    void featureDecisionCarriesTheResolvedVariantAndReason() {
        FeatureDecision decision = new FeatureDecision("checkout-v2", "on", true, "percentage:bucket=10<25->on");

        assertTrue(decision.isOn());
        assertTrue(decision.is("on"));
        assertEquals("percentage:bucket=10<25->on", decision.reason());
    }
}
