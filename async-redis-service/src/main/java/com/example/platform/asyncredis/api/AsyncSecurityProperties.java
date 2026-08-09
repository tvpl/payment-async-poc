package com.example.platform.asyncredis.api;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;

/**
 * Authentication config for the job endpoints. An {@code X-API-Key} header is a concrete, simple
 * mechanism proportionate to this boundary; production should move to JWT/OAuth2 + mTLS.
 *
 * <p>Enabled by default: an endpoint that accepts work is closed unless someone deliberately opens
 * it, not the other way around.
 */
@ConfigurationProperties("async.redis.security")
public class AsyncSecurityProperties {

    /** The development default. Refused in production by {@link ProductionAcceptanceGuard}. */
    public static final String DEV_DEFAULT_API_KEY = "dev-key-change-me";

    private boolean enabled = true;
    private List<String> apiKeys = List.of(DEV_DEFAULT_API_KEY);

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
