package com.example.payments.api.config;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSecurityGuardUnitTest {

    private static final List<String> VALID_KEYS = List.of("prod-issued-key");
    private static final Map<String, List<String>> VALID_TENANTS = Map.of("key-hash", List.of("tenant-a"));

    @Test
    void acceptsCompleteStrictProductionIdentityConfiguration() {
        assertDoesNotThrow(() -> ProductionSecurityGuard.validate(
                "https://idp.example.test/.well-known/jwks.json", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, VALID_TENANTS));
    }

    @Test
    void rejectsMissingAudience() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                " ", Duration.ZERO, true, VALID_KEYS, VALID_TENANTS));
    }

    @Test
    void rejectsInsecureJwksEndpoint() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "http://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, VALID_TENANTS));
    }

    @Test
    void rejectsInsecureIssuer() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "http://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, VALID_TENANTS));
    }

    @Test
    void rejectsUnimplementedClockSkew() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ofSeconds(30), true, VALID_KEYS, VALID_TENANTS));
    }

    @Test
    void rejectsApiKeyAuthDisabled() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, false, VALID_KEYS, VALID_TENANTS));
    }

    @Test
    void rejectsEmptyApiKeyList() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, List.of(), VALID_TENANTS));
    }

    @Test
    void rejectsDevelopmentDefaultApiKey() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, List.of("dev-key-change-me"), VALID_TENANTS));
    }

    @Test
    void rejectsBlankApiKey() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, List.of(" "), VALID_TENANTS));
    }

    /** Boot guard edge case: an empty tenant binding must fail production boot (TEN-01/02/03). */
    @Test
    void rejectsEmptyTenantBinding() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, Map.of()));
    }

    @Test
    void rejectsMissingTenantBinding() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, null));
    }

    @Test
    void rejectsTenantBindingEntryWithNoTenants() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, Map.of("key-hash", List.of())));
    }

    @Test
    void rejectsTenantBindingEntryWithABlankTenantId() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, Map.of("key-hash", List.of(" "))));
    }
}
