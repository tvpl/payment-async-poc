package com.example.platform.featurecontrol.context;

import java.util.Map;
import java.util.Set;

/**
 * The evaluation subject: who/what a flag is being resolved for. Carries the identity extracted
 * from the JWT (userId, tenant, groups) plus free-form attributes. It is transport-agnostic — the
 * resolver never touches HTTP or JWT types, only this value object — which is what lets the same
 * lib work across 30+ apps regardless of how they authenticate.
 *
 * <p>{@link #bucketingKey()} is the <strong>stable</strong> key used for deterministic A/B
 * bucketing: the {@code userId} when known (so a user always lands in the same bucket across
 * services and requests — "sticky"), otherwise an anonymous id attribute, otherwise the literal
 * {@code "anonymous"} (which buckets all anonymous traffic together — callers wanting per-session
 * stickiness should pass an {@code anonId}).
 */
public final class FeatureContext {

    public static final String ATTR_ANON_ID = "anonId";

    private final String userId;
    private final String tenantId;
    private final Set<String> groups;
    private final Map<String, String> attributes;

    private FeatureContext(String userId, String tenantId, Set<String> groups,
                           Map<String, String> attributes) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.groups = groups == null ? Set.of() : Set.copyOf(groups);
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** An anonymous context whose bucketing is derived from the given stable id. */
    public static FeatureContext anonymous(String anonId) {
        return builder().attribute(ATTR_ANON_ID, anonId).build();
    }

    public String userId() {
        return userId;
    }

    public String tenantId() {
        return tenantId;
    }

    public Set<String> groups() {
        return groups;
    }

    public boolean inGroup(String group) {
        return groups.contains(group);
    }

    public boolean inAnyGroup(Set<String> candidates) {
        for (String g : groups) {
            if (candidates.contains(g)) {
                return true;
            }
        }
        return false;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public String attribute(String key) {
        return attributes.get(key);
    }

    public boolean isAuthenticated() {
        return userId != null && !userId.isBlank();
    }

    /** Stable key for deterministic bucketing; see class docs. Never null. */
    public String bucketingKey() {
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        String anon = attributes.get(ATTR_ANON_ID);
        if (anon != null && !anon.isBlank()) {
            return anon;
        }
        return "anonymous";
    }

    public static final class Builder {
        private String userId;
        private String tenantId;
        private Set<String> groups = Set.of();
        private final java.util.Map<String, String> attributes = new java.util.HashMap<>();

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder groups(Set<String> groups) {
            this.groups = groups;
            return this;
        }

        public Builder attribute(String key, String value) {
            if (key != null && value != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        public FeatureContext build() {
            return new FeatureContext(userId, tenantId, groups, attributes);
        }
    }
}
