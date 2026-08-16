package com.example.payments.api.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * API-key authentication config. A concrete, simple mechanism for the PoC; production
 * should move to JWT/OAuth2 + mTLS (see the workspace doc docs/production-evidence.md).
 */
@ConfigurationProperties("payment.security")
public class SecurityProperties {

    private boolean enabled = true;
    private List<String> apiKeys = List.of("dev-key-change-me");
    /** Binding hash(api-key) -&gt; authorized tenants, used by {@link com.example.payments.api.tenant.TenantResolver}. */
    private Map<String, List<String>> tenants = Map.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public Map<String, List<String>> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, List<String>> tenants) {
        this.tenants = tenants;
    }
}
