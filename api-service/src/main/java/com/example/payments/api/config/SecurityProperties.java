package com.example.payments.api.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;

/**
 * API-key authentication config. A concrete, simple mechanism for the PoC; production
 * should move to JWT/OAuth2 + mTLS (see docs/15-prontidao-producao.md).
 */
@ConfigurationProperties("payment.security")
public class SecurityProperties {

    private boolean enabled = true;
    private List<String> apiKeys = List.of("dev-key-change-me");

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
}
