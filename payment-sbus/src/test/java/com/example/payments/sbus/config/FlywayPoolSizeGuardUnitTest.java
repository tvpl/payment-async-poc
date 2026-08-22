package com.example.payments.sbus.config;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayPoolSizeGuardUnitTest {

    @Test
    void rejectsAPoolOfOneWhenFlywayIsEnabled() {
        ConfigurationException exception = assertThrows(ConfigurationException.class,
                () -> FlywayPoolSizeGuard.validate("default", 1, true));

        assertTrue(exception.getMessage().contains("Flyway"),
                "the failure must name Flyway, not just report the bare pool size: "
                        + exception.getMessage());
    }

    @Test
    void acceptsTheDocumentedMinimumOfTwoWhenFlywayIsEnabled() {
        assertDoesNotThrow(() -> FlywayPoolSizeGuard.validate("default", 2, true));
    }

    @Test
    void acceptsAPoolLargerThanTheMinimum() {
        assertDoesNotThrow(() -> FlywayPoolSizeGuard.validate("default", 10, true));
    }

    @Test
    void allowsAPoolOfOneWhenFlywayIsNotEnabledForThatDatasource() {
        assertDoesNotThrow(() -> FlywayPoolSizeGuard.validate("default", 1, false));
    }
}
