package com.example.platform.featurecontrol.version;

import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.resolver.FeatureResolver;
import jakarta.inject.Singleton;

/**
 * Resolves the effective API version for the "v0" scenario: ship a beta version to a restricted
 * group while everyone else stays on the stable version. Two entry paths, one gate:
 *
 * <ul>
 *   <li><b>Explicit</b> — the frontend hits {@code /v0} or sends {@code X-Api-Version: v0}. The beta
 *       version is granted only if the caller is eligible for the gating flag (e.g. in the
 *       {@code v0-testers} group); otherwise it transparently falls back to stable.</li>
 *   <li><b>Feature-gated default</b> — no explicit version: eligible callers get beta, others stable.</li>
 * </ul>
 *
 * The eligibility itself is just a flag (typically {@code ALLOWLIST}), so v0 access is managed the
 * same way as every other feature — and can be widened at runtime via the admin API.
 */
@Singleton
public class ApiVersionResolver {

    private final FeatureResolver resolver;

    public ApiVersionResolver(FeatureResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @param flag             gating flag (e.g. {@code payment-api-v0})
     * @param ctx              caller context from the JWT
     * @param requestedVersion explicit version from path/header, or {@code null}
     * @param betaVersion      the gated version name (e.g. {@code v0})
     * @param stableVersion    the default version name (e.g. {@code v1})
     */
    public String resolve(String flag, FeatureContext ctx, String requestedVersion,
                          String betaVersion, String stableVersion) {
        boolean betaAvailable = resolver.isEnabled(flag, ctx);
        if (requestedVersion != null && requestedVersion.equalsIgnoreCase(betaVersion)) {
            // Asked for beta explicitly — grant only if eligible, else fall back to stable.
            return betaAvailable ? betaVersion : stableVersion;
        }
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            // Any other explicit version is honored as-is (stable or a pinned version).
            return requestedVersion;
        }
        // No explicit ask: feature-gated default.
        return betaAvailable ? betaVersion : stableVersion;
    }

    /** Whether the caller is eligible for the beta/v0 version at all. */
    public boolean betaAvailable(String flag, FeatureContext ctx) {
        return resolver.isEnabled(flag, ctx);
    }
}
