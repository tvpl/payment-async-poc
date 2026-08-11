package com.example.platform.featuredemo.config;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-01/SEC-02: "rotas demo/admin não aparecem em PRD." feature-demo is permanently
 * NON_PRODUCTION (AD-005) — this proves the app refuses to boot under {@code prod} at all, which is
 * a strict superset of excluding individual demo/admin beans from the graph: nothing starts, so
 * nothing is reachable.
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
        // Guards against a deploy config that sets MICRONAUT_ENVIRONMENTS=prod,cloud (or similar) -
        // @Requires(env="prod") must still fire when "prod" is one of several active environments.
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
        // Regression control: the guard must not block legitimate non-prod usage of the same config.
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, minimalProperties(), "guard-it");
        try {
            assertTrue(server.isRunning());
        } finally {
            server.close();
        }
    }
}
