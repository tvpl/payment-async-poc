package com.example.payments.sbus.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.exceptions.ConfigurationException;

import java.net.URI;
import java.time.Duration;

/** Fails production startup before serving traffic when identity boundaries are incomplete. */
@Context
@Requires(env = "prod")
public final class ProductionSecurityGuard {

    public ProductionSecurityGuard(
            @Value("${micronaut.security.token.jwt.signatures.jwks.idp.url}") String jwksUrl,
            @Value("${micronaut.security.token.jwt.claims-validators.issuer}") String issuer,
            @Value("${micronaut.security.token.jwt.claims-validators.audience}") String audience,
            @Value("${sbus.security.clock-skew}") Duration clockSkew,
            @Value("${payments.avro.auto-register}") boolean autoRegister) {
        validate(jwksUrl, issuer, audience, clockSkew, autoRegister);
    }

    static void validate(String jwksUrl, String issuer, String audience,
                         Duration clockSkew, boolean autoRegister) {
        requireHttps("JWKS URL", jwksUrl);
        requireHttps("JWT issuer", issuer);
        if (audience == null || audience.isBlank()) {
            throw new ConfigurationException("JWT audience is required in production");
        }
        if (!Duration.ZERO.equals(clockSkew)) {
            throw new ConfigurationException(
                    "SBUS clock-skew must be 0s because the active JWT validator is strict");
        }
        if (autoRegister) {
            throw new ConfigurationException("Avro auto-registration must be disabled in production");
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
