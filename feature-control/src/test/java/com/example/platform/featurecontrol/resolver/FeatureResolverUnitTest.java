package com.example.platform.featurecontrol.resolver;

import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.model.FeatureDecision;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import com.example.platform.featurecontrol.model.Variant;
import com.example.platform.featurecontrol.spi.FlagSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureResolverUnitTest {

    /** In-memory flag source so the resolver logic is tested in isolation (no Redis/YAML). */
    private static final class MapFlagSource implements FlagSource {
        private final Map<String, FlagDefinition> map = new HashMap<>();

        MapFlagSource put(FlagDefinition d) {
            map.put(d.name(), d);
            return this;
        }

        @Override
        public Optional<FlagDefinition> find(String name) {
            return Optional.ofNullable(map.get(name));
        }
    }

    private FeatureResolver resolver(FlagSource source) {
        return new FeatureResolver(source);
    }

    private FeatureContext user(String id, String... groups) {
        return FeatureContext.builder().userId(id).groups(Set.of(groups)).build();
    }

    @Test
    void unknownFlagDefaultsOff() {
        FeatureDecision d = resolver(new MapFlagSource()).evaluate("nope", user("u1"));
        assertFalse(d.isOn());
        assertEquals("unknown:default-off", d.reason());
    }

    @Test
    void disabledFlagIsOff() {
        FlagSource src = new MapFlagSource().put(new FlagDefinition(
                "f", FlagType.BOOLEAN, false, 0, null, null, null, "on", "off"));
        FeatureDecision d = resolver(src).evaluate("f", user("u1"));
        assertFalse(d.isOn());
        assertEquals("disabled", d.reason());
    }

    @Test
    void booleanToggleOnForEveryone() {
        FlagSource src = new MapFlagSource().put(new FlagDefinition(
                "kill-switch", FlagType.BOOLEAN, true, 0, null, null, null, "B", "A"));
        FeatureResolver r = resolver(src);
        assertTrue(r.isEnabled("kill-switch", user("anyone")));
        assertEquals("B", r.variant("kill-switch", user("someone-else")));
    }

    @Test
    void allowlistOnlyForUsersOrGroups() {
        FlagSource src = new MapFlagSource().put(new FlagDefinition(
                "payment-api-v0", FlagType.ALLOWLIST, true, 0,
                Set.of("alice"), Set.of("v0-testers"), null, "v0", "v1"));
        FeatureResolver r = resolver(src);

        FeatureDecision byUser = r.evaluate("payment-api-v0", user("alice"));
        assertTrue(byUser.isOn());
        assertEquals("v0", byUser.variant());
        assertEquals("allowlist:user", byUser.reason());

        FeatureDecision byGroup = r.evaluate("payment-api-v0", user("bob", "v0-testers"));
        assertTrue(byGroup.isOn());
        assertEquals("allowlist:group", byGroup.reason());

        FeatureDecision outsider = r.evaluate("payment-api-v0", user("carol"));
        assertFalse(outsider.isOn());
        assertEquals("v1", outsider.variant());
        assertEquals("allowlist:excluded", outsider.reason());
    }

    @Test
    void percentageZeroAndHundredAreDeterministicBounds() {
        FlagSource none = new MapFlagSource().put(new FlagDefinition(
                "p0", FlagType.PERCENTAGE, true, 0, null, null, null, "on", "off"));
        FlagSource all = new MapFlagSource().put(new FlagDefinition(
                "p100", FlagType.PERCENTAGE, true, 100, null, null, null, "on", "off"));
        for (int i = 0; i < 500; i++) {
            assertFalse(resolver(none).isEnabled("p0", user("u" + i)));
            assertTrue(resolver(all).isEnabled("p100", user("u" + i)));
        }
    }

    @Test
    void percentageIsStickyPerUser() {
        FlagSource src = new MapFlagSource().put(new FlagDefinition(
                "ab", FlagType.PERCENTAGE, true, 30, null, null, null, "on", "off"));
        FeatureResolver r = resolver(src);
        boolean first = r.isEnabled("ab", user("stable-user"));
        for (int i = 0; i < 200; i++) {
            assertEquals(first, r.isEnabled("ab", user("stable-user")));
        }
    }

    @Test
    void allowlistOverridesPercentage() {
        FlagSource src = new MapFlagSource().put(new FlagDefinition(
                "ab", FlagType.PERCENTAGE, true, 0, Set.of("vip"), Set.of("beta"),
                null, "on", "off"));
        FeatureResolver r = resolver(src);
        // 0% means nobody by percentage, but allowlisted user/group is pinned on.
        FeatureDecision vip = r.evaluate("ab", user("vip"));
        assertTrue(vip.isOn());
        assertTrue(vip.reason().endsWith(":override"));
        assertTrue(r.isEnabled("ab", user("x", "beta")));
        assertFalse(r.isEnabled("ab", user("random")));
    }

    @Test
    void variantWeightedPickIsDeterministic() {
        FlagSource src = new MapFlagSource().put(new FlagDefinition(
                "engine", FlagType.VARIANT, true, 0, null, null,
                List.of(new Variant("a", 50), new Variant("b", 50)), "on", "off"));
        FeatureResolver r = resolver(src);
        String v = r.variant("engine", user("u123"));
        assertTrue(v.equals("a") || v.equals("b"));
        assertEquals(v, r.variant("engine", user("u123")));
    }
}
