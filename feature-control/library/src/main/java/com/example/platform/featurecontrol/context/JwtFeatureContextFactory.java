package com.example.platform.featurecontrol.context;

import io.micronaut.security.authentication.Authentication;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link FeatureContext} from a validated Micronaut Security {@link Authentication}
 * (i.e. a verified JWT). This is the single place that knows the claim conventions, so the
 * resolver stays JWT-agnostic:
 *
 * <ul>
 *   <li>{@code userId} = {@code Authentication.getName()} (the JWT {@code sub}).</li>
 *   <li>{@code groups} = the security {@code roles} <em>plus</em> a {@code groups} claim if present
 *       (covers both role-based and group-based tokens).</li>
 *   <li>{@code tenantId} = the {@code tenant} claim when present.</li>
 * </ul>
 *
 * <p>Kept dependency-light (plain static helpers) so any app can call it. Apps that are not on
 * Micronaut Security can build a {@link FeatureContext} directly via its builder.
 */
public final class JwtFeatureContextFactory {

    public static final String CLAIM_GROUPS = "groups";
    public static final String CLAIM_TENANT = "tenant";

    private JwtFeatureContextFactory() {
    }

    /** @return context for the given authentication, or an anonymous context when {@code null}. */
    public static FeatureContext from(Authentication authentication, String anonId) {
        if (authentication == null) {
            return FeatureContext.anonymous(anonId);
        }
        Map<String, Object> attrs = authentication.getAttributes();
        Set<String> groups = new LinkedHashSet<>(authentication.getRoles());
        addClaim(groups, attrs.get(CLAIM_GROUPS));

        FeatureContext.Builder builder = FeatureContext.builder()
                .userId(authentication.getName())
                .groups(groups);

        Object tenant = attrs.get(CLAIM_TENANT);
        if (tenant != null) {
            builder.tenantId(tenant.toString());
        }
        if (anonId != null) {
            builder.attribute(FeatureContext.ATTR_ANON_ID, anonId);
        }
        return builder.build();
    }

    private static void addClaim(Set<String> target, Object claim) {
        if (claim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    target.add(item.toString());
                }
            }
        } else if (claim instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,\\s]+")) {
                if (!part.isBlank()) {
                    target.add(part);
                }
            }
        }
    }
}
