package com.example.platform.featurecontrol.resolver;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.spi.FlagSource;
import jakarta.inject.Singleton;

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
 */
@Singleton
public class MasterSwitch {

    /** Reserved flag name that, when enabled, kills all feature evaluation. */
    public static final String KILL_FLAG = "__kill_switch__";

    private final FeatureSettings settings;
    private final FlagSource flagSource;

    public MasterSwitch(FeatureSettings settings, FlagSource flagSource) {
        this.settings = settings;
        this.flagSource = flagSource;
    }

    /** @return true if feature evaluation is globally disabled right now. */
    public boolean isKilled() {
        if (!settings.isMasterEnabled()) {
            return true;
        }
        return flagSource.find(KILL_FLAG).map(f -> f.enabled()).orElse(false);
    }
}
