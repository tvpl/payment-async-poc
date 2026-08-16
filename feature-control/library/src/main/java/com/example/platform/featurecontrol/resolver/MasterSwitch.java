package com.example.platform.featurecontrol.resolver;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.spi.FlagSource;
import com.example.platform.featurecontrol.store.TrinaryFlagSource;
import jakarta.inject.Singleton;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global kill-switch consulted before any flag is evaluated. Two ways to trip it:
 *
 * <ul>
 *   <li><b>Static</b> — {@code platform.features.master-enabled=false} in config (a break-glass
 *       deploy setting).</li>
 *   <li><b>Dynamic</b> — enable the reserved flag {@code __kill_switch__} at runtime via the admin API
 *       ({@code PUT /admin/features/__kill_switch__ {"enabled":true}}). Propagates like any flag.</li>
 * </ul>
 *
 * When tripped, every decision resolves to its off/default variant with {@code reason=kill-switch} —
 * a fail-safe, never fail-open, master off. Reuses the existing {@link FlagSource} for the dynamic
 * check, so no extra Redis wiring.
 *
 * <p><strong>AUD-02 — the latch:</strong> {@link com.example.platform.featurecontrol.source.StalePolicy}'s
 * safe direction is "off"; a kill-switch's safe direction is the opposite, "armed". So a Redis
 * failure or a stale-policy fallback must never be read as "not killed" — that is exactly when a kill
 * switch matters most. When the injected {@link FlagSource} also exposes
 * {@link TrinaryFlagSource#findTrinary}, {@code isKilled()} tracks the last confirmed state in
 * {@link #lastKnownKilled} and only updates it on a genuinely fresh, successful read (FOUND or
 * ABSENT); on UNAVAILABLE it returns the latch unchanged. Cold start (no prior read) starts
 * disarmed — without local storage there is no way to know the dynamic state before the first
 * successful read, so {@code master-enabled: false} in YAML is the cold-start break-glass, not this
 * latch.
 */
@Singleton
public class MasterSwitch {

    /** Reserved flag name that, when enabled, kills all feature evaluation. */
    public static final String KILL_FLAG = "__kill_switch__";

    private final FeatureSettings settings;
    private final FlagSource flagSource;
    private final AtomicBoolean lastKnownKilled = new AtomicBoolean(false);

    public MasterSwitch(FeatureSettings settings, FlagSource flagSource) {
        this.settings = settings;
        this.flagSource = flagSource;
    }

    /** @return true if feature evaluation is globally disabled right now. */
    public boolean isKilled() {
        if (!settings.isMasterEnabled()) {
            return true;
        }
        if (flagSource instanceof TrinaryFlagSource trinary) {
            return resolveViaLatch(trinary.findTrinary(KILL_FLAG));
        }
        // No trinary support (e.g. a plain FlagSource test double, or YAML-only wiring that never
        // implements it): fall back to the previous collapsed-Optional behavior.
        return flagSource.find(KILL_FLAG).map(FlagDefinition::enabled).orElse(false);
    }

    private boolean resolveViaLatch(TrinaryFlagSource.LookupResult result) {
        return switch (result.outcome()) {
            case FOUND -> {
                boolean killed = result.served().map(FlagDefinition::enabled).orElse(false);
                lastKnownKilled.set(killed);
                yield killed;
            }
            case ABSENT -> {
                // Redis is healthy and says the key genuinely doesn't exist: a legitimate removal.
                lastKnownKilled.set(false);
                yield false;
            }
            case UNAVAILABLE -> lastKnownKilled.get();
        };
    }
}
