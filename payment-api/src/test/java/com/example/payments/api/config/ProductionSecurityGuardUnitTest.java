package com.example.payments.api.config;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSecurityGuardUnitTest {

    private static final List<String> VALID_KEYS = List.of("prod-issued-key");

    @Test
    void acceptsCompleteStrictProductionIdentityConfiguration() {
        assertDoesNotThrow(() -> ProductionSecurityGuard.validate(
                "https://idp.example.test/.well-known/jwks.json", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS));
    }

    @Test
    void rejectsMissingAudience() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                " ", Duration.ZERO, true, VALID_KEYS));
    }

    @Test
    void rejectsInsecureJwksEndpoint() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "http://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS));
    }

    @Test
    void rejectsInsecureIssuer() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "http://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS));
    }

    @Test
    void rejectsUnimplementedClockSkew() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ofSeconds(30), true, VALID_KEYS));
    }

    @Test
    void rejectsApiKeyAuthDisabled() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, false, VALID_KEYS));
    }

    @Test
    void rejectsEmptyApiKeyList() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, List.of()));
    }

    @Test
    void rejectsDevelopmentDefaultApiKey() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, List.of("dev-key-change-me")));
    }

    @Test
    void rejectsBlankApiKey() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, List.of(" ")));
    }
}
