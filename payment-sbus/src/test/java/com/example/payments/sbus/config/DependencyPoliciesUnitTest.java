package com.example.payments.sbus.config;

import io.micronaut.context.exceptions.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;

import static com.example.payments.sbus.config.DependencyPolicies.Dependency.KAFKA;
import static com.example.payments.sbus.config.DependencyPolicies.Dependency.POSTGRESQL;
import static com.example.payments.sbus.config.DependencyPolicies.Dependency.REDIS;
import static com.example.payments.sbus.config.DependencyPolicies.Dependency.SCHEMA_REGISTRY;
import static com.example.payments.sbus.config.DependencyPolicies.RecoverableState.CLAIMED_OUTBOX;
import static com.example.payments.sbus.config.DependencyPolicies.RecoverableState.KAFKA_RECORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyPoliciesUnitTest {

    @Test
    void definesRecoverableStateForEveryRequiredDependency() {
        var policies = validPolicies();

        assertEquals(CLAIMED_OUTBOX, policies.get(KAFKA).recoverableState());
        assertEquals(KAFKA_RECORD, policies.get(POSTGRESQL).recoverableState());
        assertEquals(CLAIMED_OUTBOX, policies.get(REDIS).recoverableState());
        assertEquals(KAFKA_RECORD, policies.get(SCHEMA_REGISTRY).recoverableState());
        assertTrue(policies.values().stream().allMatch(DependencyPolicies.Budget::requiredForReadiness));
    }

    @Test
    void rejectsUnboundedTimeout() {
        var policies = validPolicies();
        policies.put(KAFKA, new DependencyPolicies.Budget(Duration.ZERO, 2, true, CLAIMED_OUTBOX));

        assertThrows(ConfigurationException.class, () -> DependencyPolicies.validated(policies));
    }

    @Test
    void rejectsMissingRetryAttempt() {
        var policies = validPolicies();
        policies.put(POSTGRESQL, new DependencyPolicies.Budget(Duration.ofSeconds(1), 0, true, KAFKA_RECORD));

        assertThrows(ConfigurationException.class, () -> DependencyPolicies.validated(policies));
    }

    @Test
    void rejectsDependencyExcludedFromReadiness() {
        var policies = validPolicies();
        policies.put(REDIS, new DependencyPolicies.Budget(Duration.ofSeconds(1), 1, false, CLAIMED_OUTBOX));

        assertThrows(ConfigurationException.class, () -> DependencyPolicies.validated(policies));
    }

    private static EnumMap<DependencyPolicies.Dependency, DependencyPolicies.Budget> validPolicies() {
        var policies = new EnumMap<DependencyPolicies.Dependency, DependencyPolicies.Budget>(
                DependencyPolicies.Dependency.class);
        policies.put(KAFKA, new DependencyPolicies.Budget(Duration.ofSeconds(3), 3, true, CLAIMED_OUTBOX));
        policies.put(POSTGRESQL, new DependencyPolicies.Budget(Duration.ofSeconds(2), 2, true, KAFKA_RECORD));
        policies.put(REDIS, new DependencyPolicies.Budget(Duration.ofSeconds(1), 1, true, CLAIMED_OUTBOX));
        policies.put(SCHEMA_REGISTRY, new DependencyPolicies.Budget(Duration.ofSeconds(2), 2, true, KAFKA_RECORD));
        return policies;
    }
}
