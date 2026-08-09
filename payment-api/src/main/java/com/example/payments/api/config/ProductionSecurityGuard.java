package com.example.payments.api.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.exceptions.ConfigurationException;

import java.net.URI;
import java.time.Duration;
import java.util.List;

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
            @Value("${payment.security.api-keys}") List<String> apiKeys) {
        validate(jwksUrl, issuer, audience, clockSkew, apiKeyAuthEnabled, apiKeys);
    }

    static void validate(String jwksUrl, String issuer, String audience, Duration clockSkew,
                         boolean apiKeyAuthEnabled, List<String> apiKeys) {
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
