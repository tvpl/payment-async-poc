package com.example.platform.featurecontrol.source;

import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import io.micronaut.core.annotation.Nullable;

import java.util.Optional;

/**
 * FTR-02: decides what {@code RedisFlagSource} serves when a Redis refresh fails — last-known-good
 * (within {@code max-stale}), the YAML baseline, or a fail-closed forced-off definition — with a
 * maximum observable age. Pure and Redis-free so every combination is a fast, deterministic unit
 * test.
 */
public final class StalePolicy {

    private StalePolicy() {
    }

    /**
     * @param name              the flag name (used to synthesize a fail-closed definition when there
     *                          is no last-known-good value at all)
     * @param lastKnownGood     the last successfully fetched definition, or {@code null} if Redis has
     *                          never answered for this key
     * @param ageMillis         how long ago the last successful fetch happened; ignored when
     *                          {@code lastKnownGood} is {@code null}
     * @param maxStaleMillis    the maximum age at which {@code lastKnownGood} may still be served
     * @param fallback          what to do once the value is missing or older than {@code max-stale}
     * @return {@link Optional#empty()} to defer to the baseline, or a present definition (the
     *         last-known-good value, or a synthetic forced-off one under {@link StaleFallback#FAIL_CLOSED})
     */
    public static Optional<FlagDefinition> apply(String name, @Nullable FlagDefinition lastKnownGood,
                                                  long ageMillis, long maxStaleMillis,
                                                  StaleFallback fallback) {
        if (lastKnownGood != null && ageMillis <= maxStaleMillis) {
            return Optional.of(lastKnownGood);
        }
        if (fallback == StaleFallback.FAIL_CLOSED) {
            return Optional.of(forceOff(name, lastKnownGood));
        }
        return Optional.empty();
    }

    /**
     * A minimal always-off definition. Deliberately {@link FlagType#BOOLEAN} regardless of the
     * original type: a VARIANT/PERCENTAGE last-known-good value can't be safely "forced off" by
     * emptying its variants/allowlist (FlagDefinition's own validation rejects an empty VARIANT
     * list — see T47/FTR-01), and none of that matters anyway once {@code enabled=false} short-circuits
     * {@code FeatureResolver} before the type-specific branch runs.
     */
    private static FlagDefinition forceOff(String name, @Nullable FlagDefinition lastKnownGood) {
        String off = lastKnownGood == null ? null : lastKnownGood.offVariant();
        return new FlagDefinition(name, FlagType.BOOLEAN, false, 0, null, null, null, off, off);
    }
}
