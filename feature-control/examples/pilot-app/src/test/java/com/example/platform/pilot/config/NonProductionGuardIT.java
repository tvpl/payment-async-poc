package com.example.platform.pilot.config;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-01/SEC-02: "rotas demo/admin não aparecem em PRD." pilot-app is permanently NON_PRODUCTION
 * (AD-005) — see feature-demo's {@code NonProductionGuardIT} for the sibling example's mirror test.
 */
class NonProductionGuardIT {

    private static Map<String, Object> minimalProperties() {
        return Map.of(
                "micronaut.security.token.jwt.signatures.secret.generator.secret",
                "test-only-signing-value-with-at-least-32-bytes",
                "redis.uri", "redis://localhost:6379");
    }

    @Test
    void startupIsRefusedUnderTheProdEnvironment() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                ApplicationContext.builder()
                        .environments("prod")
                        .properties(minimalProperties())
                        .start());

        assertTrue(messages(failure).contains("NON_PRODUCTION example"),
                "expected the NON_PRODUCTION refusal message in the cause chain, got: " + messages(failure));
    }

    @Test
    void startupIsRefusedWhenProdIsCombinedWithAnotherEnvironment() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                ApplicationContext.builder()
                        .environments("prod", "cloud")
                        .properties(minimalProperties())
                        .start());

        assertTrue(messages(failure).contains("NON_PRODUCTION example"));
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    @Test
    void startupSucceedsUnderANonProdEnvironmentWithTheSameProperties() {
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, minimalProperties(), "guard-it");
        try {
            assertTrue(server.isRunning());
        } finally {
            server.close();
        }
    }
}
