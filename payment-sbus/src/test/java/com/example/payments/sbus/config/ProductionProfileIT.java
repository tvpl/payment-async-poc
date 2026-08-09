package com.example.payments.sbus.config;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionProfileIT {

    @Test
    void productionProfileUsesAsymmetricClaimsValidationAndNoSharedSecret() throws IOException {
        String production = Files.readString(Path.of("src/main/resources/application-prod.yml"));

        assertTrue(production.contains("jwks:"));
        assertTrue(production.contains("issuer: ${SBUS_JWT_ISSUER}"));
        assertTrue(production.contains("audience: ${SBUS_JWT_AUDIENCE}"));
        assertTrue(production.contains("expiration: true"));
        assertTrue(production.contains("not-before: true"));
        assertFalse(production.contains("secret:"));
    }

    @Test
    void invalidProductionIdentityConfigurationFailsContextStartup() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                ApplicationContext.builder()
                        .environments("prod")
                        .properties(Map.ofEntries(
                                Map.entry("micronaut.server.enabled", false),
                                Map.entry("kafka.enabled", false),
                                Map.entry("micronaut.scheduling.enabled", false),
                                Map.entry("datasources.default.enabled", false),
                                Map.entry("flyway.datasources.default.enabled", false),
                                Map.entry("micronaut.security.token.jwt.signatures.jwks.idp.url",
                                        "https://idp.example.test/jwks"),
                                Map.entry("micronaut.security.token.jwt.claims-validators.issuer",
                                        "https://idp.example.test/"),
                                Map.entry("micronaut.security.token.jwt.claims-validators.audience", " "),
                                Map.entry("sbus.security.clock-skew", "0s"),
                                Map.entry("payments.avro.auto-register", false)))
                        .start());

        assertTrue(messages(failure).contains("JWT audience is required in production"));
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }
}
