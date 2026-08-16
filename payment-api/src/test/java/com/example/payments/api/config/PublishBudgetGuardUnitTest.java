package com.example.payments.api.config;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUDG-01: {@code payment.publish-budget} must leave {@code payment.simulation.wait-timeout}
 * room to observe a publish failure.
 */
class PublishBudgetGuardUnitTest {

    @Test
    void acceptsABudgetStrictlyBelowTheWaitTimeout() {
        assertDoesNotThrow(() -> PublishBudgetGuard.validate(Duration.ofMillis(1500), Duration.ofSeconds(3)));
    }

    @Test
    void rejectsABudgetEqualToTheWaitTimeout() {
        assertThrows(ConfigurationException.class,
                () -> PublishBudgetGuard.validate(Duration.ofSeconds(3), Duration.ofSeconds(3)));
    }

    @Test
    void rejectsABudgetAboveTheWaitTimeout() {
        assertThrows(ConfigurationException.class,
                () -> PublishBudgetGuard.validate(Duration.ofSeconds(5), Duration.ofSeconds(3)));
    }

    /** "Teste de contexto": a full boot with an invalid budget must fail application startup. */
    @Test
    void aBudgetAtOrAboveTheWaitTimeoutFailsApplicationBoot() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                ApplicationContext.builder()
                        .properties(Map.ofEntries(
                                Map.entry("micronaut.server.enabled", false),
                                Map.entry("kafka.enabled", false),
                                Map.entry("micronaut.scheduling.enabled", false),
                                Map.entry("payment.publish-budget", "3s"),
                                Map.entry("payment.simulation.wait-timeout", "3s")))
                        .start());

        assertTrue(messages(failure).contains("payment.publish-budget"));
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }
}
