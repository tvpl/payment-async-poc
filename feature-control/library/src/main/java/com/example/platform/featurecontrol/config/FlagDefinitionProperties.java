package com.example.platform.featurecontrol.config;

import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import com.example.platform.featurecontrol.model.Variant;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One flag's baseline definition in YAML. Bound per entry under {@code platform.features.flags.*}
 * (the map key becomes the flag name). Example:
 *
 * <pre>
 * platform:
 *   features:
 *     flags:
 *       payment-api-v0:
 *         type: ALLOWLIST
 *         enabled: true
 *         allowed-groups: [v0-testers]
 *         on-variant: v0
 *         off-variant: v1
 *       checkout-engine:
 *         type: PERCENTAGE
 *         enabled: true
 *         percentage: 10          # 10% -> on (B), 90% -> off (A)
 *         on-variant: engine-b
 *         off-variant: engine-a
 * </pre>
 *
 * Variants for {@code VARIANT} flags are expressed as a {@code name: weight} map for config
 * ergonomics and converted to {@link Variant} here.
 */
@EachProperty("platform.features.flags")
public class FlagDefinitionProperties {

    private final String name;

    private FlagType type = FlagType.BOOLEAN;
    private boolean enabled = false;
    private int percentage = 0;
    private Set<String> allowedUsers = Set.of();
    private Set<String> allowedGroups = Set.of();
    private Map<String, Integer> variants = Map.of();
    private String onVariant;
    private String offVariant;

    public FlagDefinitionProperties(@Parameter String name) {
        this.name = name;
    }

    public FlagDefinition toDefinition() {
        List<Variant> variantList = new ArrayList<>();
        variants.forEach((n, w) -> variantList.add(new Variant(n, w == null ? 0 : w)));
        return new FlagDefinition(name, type, enabled, percentage,
                allowedUsers, allowedGroups, variantList, onVariant, offVariant);
    }

    public String getName() {
        return name;
    }

    public FlagType getType() {
        return type;
    }

    public void setType(FlagType type) {
        this.type = type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPercentage() {
        return percentage;
    }

    public void setPercentage(int percentage) {
        this.percentage = percentage;
    }

    public Set<String> getAllowedUsers() {
        return allowedUsers;
    }

    public void setAllowedUsers(Set<String> allowedUsers) {
        this.allowedUsers = allowedUsers;
    }

    public Set<String> getAllowedGroups() {
        return allowedGroups;
    }

    public void setAllowedGroups(Set<String> allowedGroups) {
        this.allowedGroups = allowedGroups;
    }

    public Map<String, Integer> getVariants() {
        return variants;
    }

    public void setVariants(Map<String, Integer> variants) {
        this.variants = variants;
    }

    public String getOnVariant() {
        return onVariant;
    }

    public void setOnVariant(String onVariant) {
        this.onVariant = onVariant;
    }

    public String getOffVariant() {
        return offVariant;
    }

    public void setOffVariant(String offVariant) {
        this.offVariant = offVariant;
    }
}
