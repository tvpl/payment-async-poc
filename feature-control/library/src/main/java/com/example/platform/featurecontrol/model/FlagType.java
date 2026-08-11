package com.example.platform.featurecontrol.model;

/**
 * The evaluation strategy of a feature flag. One flag has exactly one type; the
 * {@code FeatureResolver} picks the branch to run from it.
 *
 * <ul>
 *   <li>{@link #BOOLEAN} — a plain on/off toggle (route 100% to A or B).</li>
 *   <li>{@link #PERCENTAGE} — deterministic A/B split by a stable bucketing key (e.g. 10%/90%).</li>
 *   <li>{@link #ALLOWLIST} — enabled only for a restricted set of users/groups (v0 testers).</li>
 *   <li>{@link #VARIANT} — weighted multivariate rollout across named variants.</li>
 * </ul>
 */
public enum FlagType {
    BOOLEAN,
    PERCENTAGE,
    ALLOWLIST,
    VARIANT
}
