package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.model.FlagDefinition;

import java.util.Optional;

/**
 * AUD-02: internal trinary read — FOUND / ABSENT / UNAVAILABLE — that {@link Optional} alone can't
 * express, since {@code find()} collapses both "no such key" and "Redis failed" to
 * {@link Optional#empty()}. Consulted only by
 * {@link com.example.platform.featurecontrol.resolver.MasterSwitch}'s kill-switch latch, which needs
 * to tell a legitimately removed flag (Redis healthy, no such key — safe to disarm) apart from a
 * Redis outage or stale-policy fallback (keep the latch armed). Not part of the public
 * {@link com.example.platform.featurecontrol.spi.FlagSource} contract; {@link RedisFlagSource} and
 * {@link CompositeFlagSource} are the only implementors.
 */
public interface TrinaryFlagSource {

    enum LookupOutcome {
        FOUND,
        ABSENT,
        UNAVAILABLE
    }

    /**
     * @param outcome how the lookup resolved
     * @param served  what the public {@link com.example.platform.featurecontrol.spi.FlagSource#find}
     *                would return for the same read — present for FOUND, empty for ABSENT, and either
     *                (per {@link com.example.platform.featurecontrol.source.StalePolicy}) for
     *                UNAVAILABLE
     */
    record LookupResult(LookupOutcome outcome, Optional<FlagDefinition> served) {

        public static LookupResult of(FlagDefinition definition) {
            return definition == null
                    ? new LookupResult(LookupOutcome.ABSENT, Optional.empty())
                    : new LookupResult(LookupOutcome.FOUND, Optional.of(definition));
        }

        public static LookupResult unavailable(Optional<FlagDefinition> served) {
            return new LookupResult(LookupOutcome.UNAVAILABLE, served);
        }
    }

    LookupResult findTrinary(String name);
}
