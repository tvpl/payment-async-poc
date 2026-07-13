package com.example.platform.featurecontrol.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Set;

/**
 * The definition of a single feature flag — the shape that lives in YAML (baseline) and in
 * Redis (dynamic override). It is intentionally a plain data record so the same JSON can be
 * read from a config file, a Redis key, or an admin API without translation.
 *
 * <p>Which fields matter depends on {@link #type()}:
 * <ul>
 *   <li>{@code BOOLEAN}: {@link #enabled()} + {@link #onVariant()}/{@link #offVariant()}.</li>
 *   <li>{@code PERCENTAGE}: {@link #percentage()} of traffic goes to {@link #onVariant()}
 *       (the "B" side), the rest to {@link #offVariant()} (the "A" side).</li>
 *   <li>{@code ALLOWLIST}: {@link #allowedUsers()} / {@link #allowedGroups()} get
 *       {@link #onVariant()}; everyone else {@link #offVariant()}.</li>
 *   <li>{@code VARIANT}: weighted pick across {@link #variants()}.</li>
 * </ul>
 *
 * <p>{@link #allowedUsers()}/{@link #allowedGroups()} are also honored as an <em>override</em>
 * on PERCENTAGE/VARIANT flags, so a restricted group can be pinned to the "on" side regardless
 * of the roll-out percentage — the common "internal testers always see v0" pattern.
 */
@Serdeable
public record FlagDefinition(
        String name,
        FlagType type,
        boolean enabled,
        int percentage,
        @Nullable Set<String> allowedUsers,
        @Nullable Set<String> allowedGroups,
        @Nullable List<Variant> variants,
        @Nullable String onVariant,
        @Nullable String offVariant) {

    public FlagDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("flag name is required");
        }
        if (type == null) {
            type = FlagType.BOOLEAN;
        }
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("percentage must be within [0,100]");
        }
        allowedUsers = allowedUsers == null ? Set.of() : Set.copyOf(allowedUsers);
        allowedGroups = allowedGroups == null ? Set.of() : Set.copyOf(allowedGroups);
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    /** Variant name returned when the flag resolves "on". Defaults to {@code "on"}. */
    public String onName() {
        return onVariant == null || onVariant.isBlank() ? "on" : onVariant;
    }

    /** Variant name returned when the flag resolves "off". Defaults to {@code "off"}. */
    public String offName() {
        return offVariant == null || offVariant.isBlank() ? "off" : offVariant;
    }
}
