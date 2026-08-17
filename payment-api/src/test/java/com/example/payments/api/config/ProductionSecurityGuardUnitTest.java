package com.example.payments.api.config;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSecurityGuardUnitTest {

    /** SEC-04: production keys are already-hashed {@code sha256:<hex>} entries, never plaintext. */
    private static final List<String> VALID_KEYS =
            List.of("sha256:2f6e0c3e0c1a6b3a5d8e9f0a1b2c3d4e5f60718293a4b5c6d7e8f9001122334");
    private static final Map<String, List<String>> VALID_TENANTS = Map.of("key-hash", List.of("tenant-a"));
    /** SEC-01: production always keeps Avro auto-registration off. */
    private static final boolean AUTO_REGISTER_OFF = false;

    @Test
    void acceptsCompleteStrictProductionIdentityConfiguration() {
        assertDoesNotThrow(() -> ProductionSecurityGuard.validate(
                "https://idp.example.test/.well-known/jwks.json", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, AUTO_REGISTER_OFF, VALID_TENANTS));
    }

    @Test
    void rejectsMissingAudience() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                " ", Duration.ZERO, true, VALID_KEYS, AUTO_REGISTER_OFF, VALID_TENANTS));
    }

    @Test
    void rejectsInsecureJwksEndpoint() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "http://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, AUTO_REGISTER_OFF, VALID_TENANTS));
    }

    @Test
    void rejectsInsecureIssuer() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "http://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, AUTO_REGISTER_OFF, VALID_TENANTS));
    }

    @Test
    void rejectsUnimplementedClockSkew() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ofSeconds(30), true, VALID_KEYS, AUTO_REGISTER_OFF, VALID_TENANTS));
    }

    @Test
    void rejectsApiKeyAuthDisabled() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, false, VALID_KEYS, AUTO_REGISTER_OFF, VALID_TENANTS));
    }

    @Test
    void rejectsEmptyApiKeyList() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, List.of(), AUTO_REGISTER_OFF, VALID_TENANTS));
    }

    @Test
    void rejectsDevelopmentDefaultApiKey() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, List.of("dev-key-change-me"), AUTO_REGISTER_OFF, VALID_TENANTS));
    }

    @Test
    void rejectsBlankApiKey() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, List.of(" "), AUTO_REGISTER_OFF, VALID_TENANTS));
    }

    /** SEC-04: production must reject a key that is not already a {@code sha256:<hex>} hash. */
    @Test
    void rejectsPlaintextApiKeyInProduction() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, List.of("prod-issued-key"), AUTO_REGISTER_OFF, VALID_TENANTS));
    }

    /** SEC-01: production must fail boot when Avro auto-registration is left on. */
    @Test
    void rejectsAvroAutoRegisterEnabledInProduction() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, true, VALID_TENANTS));
    }

    /** Boot guard edge case: an empty tenant binding must fail production boot (TEN-01/02/03). */
    @Test
    void rejectsEmptyTenantBinding() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, AUTO_REGISTER_OFF, Map.of()));
    }

    @Test
    void rejectsMissingTenantBinding() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, AUTO_REGISTER_OFF, null));
    }

    @Test
    void rejectsTenantBindingEntryWithNoTenants() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, AUTO_REGISTER_OFF, Map.of("key-hash", List.of())));
    }

    @Test
    void rejectsTenantBindingEntryWithABlankTenantId() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-api", Duration.ZERO, true, VALID_KEYS, AUTO_REGISTER_OFF, Map.of("key-hash", List.of(" "))));
    }
}
