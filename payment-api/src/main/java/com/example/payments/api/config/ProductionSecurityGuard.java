package com.example.payments.api.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.exceptions.ConfigurationException;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Fails production startup before serving traffic when identity boundaries are incomplete. */
@Context
@Requires(env = "prod")
public final class ProductionSecurityGuard {

    private static final String DEV_DEFAULT_API_KEY = "dev-key-change-me";

    public ProductionSecurityGuard(
            @Value("${micronaut.security.token.jwt.signatures.jwks.idp.url}") String jwksUrl,
            @Value("${micronaut.security.token.jwt.claims-validators.issuer}") String issuer,
            @Value("${micronaut.security.token.jwt.claims-validators.audience}") String audience,
            @Value("${payment.security.clock-skew}") Duration clockSkew,
            @Value("${payment.security.enabled}") boolean apiKeyAuthEnabled,
            @Value("${payment.security.api-keys}") List<String> apiKeys,
            SecurityProperties securityProperties) {
        validate(jwksUrl, issuer, audience, clockSkew, apiKeyAuthEnabled, apiKeys, securityProperties.getTenants());
    }

    static void validate(String jwksUrl, String issuer, String audience, Duration clockSkew,
                         boolean apiKeyAuthEnabled, List<String> apiKeys, Map<String, List<String>> tenants) {
        requireHttps("JWKS URL", jwksUrl);
        requireHttps("JWT issuer", issuer);
        if (audience == null || audience.isBlank()) {
            throw new ConfigurationException("JWT audience is required in production");
        }
        if (!Duration.ZERO.equals(clockSkew)) {
            throw new ConfigurationException(
                    "payment-api clock-skew must be 0s because the active JWT validator is strict");
        }
        if (!apiKeyAuthEnabled) {
            throw new ConfigurationException("payment.security.enabled must be true in production");
        }
        if (apiKeys == null || apiKeys.isEmpty()) {
            throw new ConfigurationException("payment.security.api-keys is required in production");
        }
        for (String key : apiKeys) {
            if (key == null || key.isBlank() || DEV_DEFAULT_API_KEY.equals(key)) {
                throw new ConfigurationException(
                        "payment.security.api-keys must not be blank or the development default in production");
            }
        }
        validateTenants(tenants);
    }

    /**
     * Boot guard for the tenant binding (TEN-01/02/03 edge case): production never serves traffic
     * with an empty or malformed {@code payment.security.tenants} binding, so no request can be
     * accepted without a tenant scope.
     */
    private static void validateTenants(Map<String, List<String>> tenants) {
        if (tenants == null || tenants.isEmpty()) {
            throw new ConfigurationException("payment.security.tenants is required in production "
                    + "(empty or missing tenant binding)");
        }
        for (Map.Entry<String, List<String>> entry : tenants.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new ConfigurationException(
                        "payment.security.tenants keys must be a non-blank API key hash");
            }
            List<String> boundTenants = entry.getValue();
            if (boundTenants == null || boundTenants.isEmpty()) {
                throw new ConfigurationException(
                        "payment.security.tenants entries must map to at least one tenant");
            }
            for (String tenant : boundTenants) {
                if (tenant == null || tenant.isBlank()) {
                    throw new ConfigurationException(
                            "payment.security.tenants entries must not contain a blank tenant id");
                }
            }
        }
    }

    private static void requireHttps(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(name + " is required in production");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(name + " must be a valid HTTPS URI", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ConfigurationException(name + " must be a valid HTTPS URI");
        }
    }
}
