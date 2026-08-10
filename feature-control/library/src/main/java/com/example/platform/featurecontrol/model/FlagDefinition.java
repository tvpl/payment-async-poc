package com.example.platform.featurecontrol.model;

import io.micronaut.core.annotation.Creator;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
 *
 * <p>{@link #version()} enables optimistic concurrency on writes (compare-and-set in Redis so two
 * admins can't silently clobber each other). {@link #bucketingSalt()} lets a set of flags share a
 * cohort (same users bucketed together) when needed; when null the flag name is the salt (the
 * default, which decorrelates flags). Both are optional and default to backward-compatible values.
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
        @Nullable String offVariant,
        long version,
        @Nullable String bucketingSalt) {

    /** Bound so a name is a safe, human-scannable Redis-key/log/metric-tag suffix (FTR-01). */
    static final int MAX_NAME_LENGTH = 100;
    /** Bound for free-text labels ({@code onVariant}/{@code offVariant}/variant names) (FTR-01). */
    static final int MAX_LABEL_LENGTH = 100;
    /** Bound for the bucketing salt (FTR-01). */
    static final int MAX_SALT_LENGTH = 200;
    /**
     * A flag name is a Redis-key suffix, a log field and a metric tag value: letters, digits,
     * {@code _.-} only, starting with a letter/digit/underscore (the reserved
     * {@link com.example.platform.featurecontrol.resolver.MasterSwitch#KILL_FLAG} starts with
     * {@code _}). No whitespace, no {@code :} (would collide with the {@code key-prefix} separator).
     */
    static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");

    @Creator
    public FlagDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("flag name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("flag name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "flag name must match " + NAME_PATTERN.pattern() + " (no whitespace or ':')");
        }
        if (type == null) {
            type = FlagType.BOOLEAN;
        }
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("percentage must be within [0,100]");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
        if (bucketingSalt != null && bucketingSalt.length() > MAX_SALT_LENGTH) {
            throw new IllegalArgumentException("bucketingSalt must be at most " + MAX_SALT_LENGTH + " characters");
        }
        if (onVariant != null && onVariant.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("onVariant must be at most " + MAX_LABEL_LENGTH + " characters");
        }
        if (offVariant != null && offVariant.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("offVariant must be at most " + MAX_LABEL_LENGTH + " characters");
        }
        allowedUsers = allowedUsers == null ? Set.of() : Set.copyOf(allowedUsers);
        allowedGroups = allowedGroups == null ? Set.of() : Set.copyOf(allowedGroups);
        variants = variants == null ? List.of() : List.copyOf(variants);

        if (type == FlagType.VARIANT) {
            if (variants.isEmpty()) {
                throw new IllegalArgumentException("VARIANT flag '" + name + "' requires at least one variant");
            }
            Set<String> seenNames = new HashSet<>();
            long totalWeight = 0;
            for (Variant candidate : variants) {
                if (candidate.name().length() > MAX_LABEL_LENGTH) {
                    throw new IllegalArgumentException(
                            "variant name must be at most " + MAX_LABEL_LENGTH + " characters");
                }
                if (!seenNames.add(candidate.name())) {
                    throw new IllegalArgumentException(
                            "VARIANT flag '" + name + "' has duplicate variant name '" + candidate.name() + "'");
                }
                totalWeight += candidate.weight();
            }
            if (totalWeight <= 0) {
                throw new IllegalArgumentException(
                        "VARIANT flag '" + name + "' requires at least one variant with weight > 0");
            }
        } else if (!variants.isEmpty()) {
            throw new IllegalArgumentException("variants are only allowed for VARIANT flags, not " + type);
        }

        if (type == FlagType.ALLOWLIST && enabled && allowedUsers.isEmpty() && allowedGroups.isEmpty()) {
            throw new IllegalArgumentException(
                    "enabled ALLOWLIST flag '" + name + "' requires at least one allowed user or group");
        }
    }

    /** Backward-compatible constructor (version=0, salt=flag name) used by existing call sites. */
    public FlagDefinition(String name, FlagType type, boolean enabled, int percentage,
                          Set<String> allowedUsers, Set<String> allowedGroups, List<Variant> variants,
                          String onVariant, String offVariant) {
        this(name, type, enabled, percentage, allowedUsers, allowedGroups, variants,
                onVariant, offVariant, 0L, null);
    }

    /** Variant name returned when the flag resolves "on". Defaults to {@code "on"}. */
    public String onName() {
        return onVariant == null || onVariant.isBlank() ? "on" : onVariant;
    }

    /** Variant name returned when the flag resolves "off". Defaults to {@code "off"}. */
    public String offName() {
        return offVariant == null || offVariant.isBlank() ? "off" : offVariant;
    }

    /** Effective bucketing salt: the explicit one, or the flag name (decorrelates flags). */
    public String effectiveSalt() {
        return bucketingSalt == null || bucketingSalt.isBlank() ? name : bucketingSalt;
    }

    /** Returns a copy with the given version (used by the admin CAS write path). */
    public FlagDefinition withVersion(long newVersion) {
        return new FlagDefinition(name, type, enabled, percentage, allowedUsers, allowedGroups,
                variants, onVariant, offVariant, newVersion, bucketingSalt);
    }
}
