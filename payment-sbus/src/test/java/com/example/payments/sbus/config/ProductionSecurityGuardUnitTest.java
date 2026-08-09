package com.example.payments.sbus.config;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSecurityGuardUnitTest {

    @Test
    void acceptsCompleteStrictProductionIdentityConfiguration() {
        assertDoesNotThrow(() -> ProductionSecurityGuard.validate(
                "https://idp.example.test/.well-known/jwks.json",
                "https://idp.example.test/", "payment-sbus", Duration.ZERO, false));
    }

    @Test
    void rejectsMissingAudience() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                " ", Duration.ZERO, false));
    }

    @Test
    void rejectsInsecureJwksEndpoint() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "http://idp.example.test/jwks", "https://idp.example.test/",
                "payment-sbus", Duration.ZERO, false));
    }

    @Test
    void rejectsInsecureIssuer() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "http://idp.example.test/",
                "payment-sbus", Duration.ZERO, false));
    }

    @Test
    void rejectsUnimplementedClockSkew() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-sbus", Duration.ofSeconds(30), false));
    }

    @Test
    void rejectsSchemaAutoRegistration() {
        assertThrows(ConfigurationException.class, () -> ProductionSecurityGuard.validate(
                "https://idp.example.test/jwks", "https://idp.example.test/",
                "payment-sbus", Duration.ZERO, true));
    }
}
