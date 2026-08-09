package com.example.platform.featurecontrol.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FTR-01: the feature definition validates fields by type, names, weights, versions, bounds and
 * invalid combinations before it can be persisted or activated. {@link FlagDefinition} is a record
 * whose compact constructor is the single choke point every caller goes through — YAML binding
 * ({@code FlagDefinitionProperties#toDefinition}), the admin write path
 * ({@code FlagAdminService#put}) and Redis deserialization all construct one, so validating here
 * enforces it "before persist/activate" everywhere at once.
 */
class FlagDefinitionValidationUnitTest {

    private static FlagDefinition boolFlag(String name, boolean enabled) {
        return new FlagDefinition(name, FlagType.BOOLEAN, enabled, 0, null, null, null, "on", "off");
    }

    // ---- name -------------------------------------------------------------------------------

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> boolFlag("", true));
    }

    @Test
    void rejectsNullName() {
        assertThrows(IllegalArgumentException.class, () -> boolFlag(null, true));
    }

    @Test
    void rejectsNameExceedingMaxLength() {
        String tooLong = "f".repeat(FlagDefinition.MAX_NAME_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> boolFlag(tooLong, true));
    }

    @Test
    void rejectsNameWithWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> boolFlag("demo toggle", true));
    }

    @Test
    void rejectsNameWithColon() {
        // ':' would collide with the Redis key-prefix separator (settings.getKeyPrefix() + name).
        assertThrows(IllegalArgumentException.class, () -> boolFlag("demo:toggle", true));
    }

    @Test
    void acceptsMinimalSingleCharacterName() {
        assertDoesNotThrow(() -> boolFlag("x", true));
    }

    @Test
    void acceptsReservedKillSwitchNameStartingWithUnderscore() {
        // com.example.platform.featurecontrol.resolver.MasterSwitch.KILL_FLAG must stay constructible.
        assertDoesNotThrow(() -> boolFlag("__kill_switch__", true));
    }

    @Test
    void acceptsNameWithMaxLength() {
        String maxLength = "f".repeat(FlagDefinition.MAX_NAME_LENGTH);
        assertDoesNotThrow(() -> boolFlag(maxLength, true));
    }

    // ---- percentage ---------------------------------------------------------------------------

    @Test
    void rejectsPercentageBelowZero() {
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "p", FlagType.PERCENTAGE, true, -1, null, null, null, "on", "off"));
    }

    @Test
    void rejectsPercentageAboveHundred() {
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "p", FlagType.PERCENTAGE, true, 101, null, null, null, "on", "off"));
    }

    @Test
    void acceptsPercentageLowerBound() {
        assertDoesNotThrow(() -> new FlagDefinition(
                "p", FlagType.PERCENTAGE, true, 0, null, null, null, "on", "off"));
    }

    @Test
    void acceptsPercentageUpperBound() {
        assertDoesNotThrow(() -> new FlagDefinition(
                "p", FlagType.PERCENTAGE, true, 100, null, null, null, "on", "off"));
    }

    // ---- version ------------------------------------------------------------------------------

    @Test
    void rejectsNegativeVersion() {
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "f", FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", -1L, null));
    }

    @Test
    void acceptsVersionZero() {
        assertDoesNotThrow(() -> new FlagDefinition(
                "f", FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 0L, null));
    }

    // ---- VARIANT combinations -------------------------------------------------------------------

    @Test
    void rejectsVariantFlagWithNoVariants() {
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "v", FlagType.VARIANT, true, 0, null, null, List.of(), "on", "off"));
    }

    @Test
    void rejectsVariantFlagWithDuplicateVariantNames() {
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "v", FlagType.VARIANT, true, 0, null, null,
                List.of(new Variant("a", 50), new Variant("a", 50)), "on", "off"));
    }

    @Test
    void rejectsVariantFlagWithAllZeroWeights() {
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "v", FlagType.VARIANT, true, 0, null, null,
                List.of(new Variant("a", 0), new Variant("b", 0)), "on", "off"));
    }

    @Test
    void acceptsVariantFlagWithAtLeastOnePositiveWeight() {
        assertDoesNotThrow(() -> new FlagDefinition(
                "v", FlagType.VARIANT, true, 0, null, null,
                List.of(new Variant("a", 1), new Variant("b", 0)), "on", "off"));
    }

    @Test
    void rejectsVariantsDeclaredOnANonVariantFlag() {
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "b", FlagType.BOOLEAN, true, 0, null, null,
                List.of(new Variant("a", 1)), "on", "off"));
    }

    @Test
    void rejectsVariantNameExceedingMaxLabelLength() {
        String tooLong = "v".repeat(FlagDefinition.MAX_LABEL_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "v", FlagType.VARIANT, true, 0, null, null,
                List.of(new Variant(tooLong, 1)), "on", "off"));
    }

    // ---- ALLOWLIST combinations ------------------------------------------------------------------

    @Test
    void rejectsEnabledAllowlistWithNoUsersOrGroups() {
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "a", FlagType.ALLOWLIST, true, 0, null, null, null, "on", "off"));
    }

    @Test
    void acceptsDisabledAllowlistWithNoUsersOrGroups() {
        // A disabled placeholder never reaches the allowlist branch (FeatureResolver checks
        // enabled() first), so an empty membership set is a legitimate draft/staged state.
        assertDoesNotThrow(() -> new FlagDefinition(
                "a", FlagType.ALLOWLIST, false, 0, null, null, null, "on", "off"));
    }

    @Test
    void acceptsEnabledAllowlistWithOnlyGroups() {
        assertDoesNotThrow(() -> new FlagDefinition(
                "a", FlagType.ALLOWLIST, true, 0, null, Set.of("beta"), null, "on", "off"));
    }

    @Test
    void acceptsEnabledAllowlistWithOnlyUsers() {
        assertDoesNotThrow(() -> new FlagDefinition(
                "a", FlagType.ALLOWLIST, true, 0, Set.of("alice"), null, null, "on", "off"));
    }

    // ---- salt / labels --------------------------------------------------------------------------

    @Test
    void rejectsBucketingSaltExceedingMaxLength() {
        String tooLong = "s".repeat(FlagDefinition.MAX_SALT_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "f", FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 0L, tooLong));
    }

    @Test
    void acceptsBucketingSaltAtMaxLength() {
        String maxLength = "s".repeat(FlagDefinition.MAX_SALT_LENGTH);
        assertDoesNotThrow(() -> new FlagDefinition(
                "f", FlagType.BOOLEAN, true, 0, null, null, null, "on", "off", 0L, maxLength));
    }

    @Test
    void rejectsOnVariantLabelExceedingMaxLength() {
        String tooLong = "o".repeat(FlagDefinition.MAX_LABEL_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "f", FlagType.BOOLEAN, true, 0, null, null, null, tooLong, "off"));
    }

    @Test
    void rejectsOffVariantLabelExceedingMaxLength() {
        String tooLong = "o".repeat(FlagDefinition.MAX_LABEL_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "f", FlagType.BOOLEAN, true, 0, null, null, null, "on", tooLong));
    }

    // ---- type defaulting (pre-existing behavior, kept honest by a regression test) --------------

    @Test
    void nullTypeDefaultsToBoolean() {
        FlagDefinition def = new FlagDefinition(
                "f", null, true, 0, null, null, null, "on", "off");
        assertEquals(FlagType.BOOLEAN, def.type());
    }
}
