package com.example.platform.asyncredis.api;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-08 allows two states and no middle: production has authentication, idempotency and an
 * admission limit, or the boundary is explicitly a non-production example. Each missing gate has to
 * stop startup, not degrade quietly.
 */
class ProductionAcceptanceGuardUnitTest {

    private static final List<String> REAL_KEYS = List.of("a-real-production-key");

    @Test
    void productionRefusesToStartWithAuthenticationDisabled() {
        ConfigurationException thrown = assertThrows(ConfigurationException.class,
                () -> ProductionAcceptanceGuard.validate(false, REAL_KEYS, true, 100));

        assertTrue(thrown.getMessage().contains("async.redis.security.enabled"), thrown.getMessage());
    }

    @Test
    void productionRefusesToStartWithNoApiKey() {
        assertThrows(ConfigurationException.class,
                () -> ProductionAcceptanceGuard.validate(true, List.of(), true, 100));
    }

    @Test
    void productionRefusesTheDevelopmentDefaultKey() {
        ConfigurationException thrown = assertThrows(ConfigurationException.class,
                () -> ProductionAcceptanceGuard.validate(
                        true, List.of(AsyncSecurityProperties.DEV_DEFAULT_API_KEY), true, 100));

        assertTrue(thrown.getMessage().contains("development default"), thrown.getMessage());
    }

    @Test
    void productionRefusesABlankApiKey() {
        assertThrows(ConfigurationException.class,
                () -> ProductionAcceptanceGuard.validate(true, List.of("  "), true, 100));
    }

    @Test
    void productionRefusesToStartWithoutRequiredIdempotency() {
        ConfigurationException thrown = assertThrows(ConfigurationException.class,
                () -> ProductionAcceptanceGuard.validate(true, REAL_KEYS, false, 100));

        assertTrue(thrown.getMessage().contains("idempotency-required"), thrown.getMessage());
    }

    @Test
    void productionRefusesToStartWithAdmissionControlDisabled() {
        ConfigurationException thrown = assertThrows(ConfigurationException.class,
                () -> ProductionAcceptanceGuard.validate(true, REAL_KEYS, true, 0));

        assertTrue(thrown.getMessage().contains("admission-limit-per-sec"), thrown.getMessage());
    }

    @Test
    void aFullyGatedProductionConfigurationStarts() {
        assertDoesNotThrow(() -> ProductionAcceptanceGuard.validate(true, REAL_KEYS, true, 100));
    }
}
