package com.example.payments.sbus.config;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyPolicyIT {

    @Test
    void bindsTheFourDependencyBudgetsAndRecoveryMatrix() {
        try (ApplicationContext context = context(Map.of())) {
            DependencyPolicies policies = context.getBean(DependencyPolicies.class);

            assertEquals(4, policies.all().size());
            assertEquals(8, policies.budget(DependencyPolicies.Dependency.KAFKA).maxAttempts());
            assertEquals(DependencyPolicies.RecoverableState.KAFKA_RECORD,
                    policies.budget(DependencyPolicies.Dependency.POSTGRESQL).recoverableState());
            assertEquals(DependencyPolicies.RecoverableState.CLAIMED_OUTBOX,
                    policies.budget(DependencyPolicies.Dependency.REDIS).recoverableState());
            assertEquals(DependencyPolicies.RecoverableState.KAFKA_RECORD,
                    policies.budget(DependencyPolicies.Dependency.SCHEMA_REGISTRY).recoverableState());
        }
    }

    @Test
    void incoherentRetentionFailsApplicationStartup() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                context(Map.of("sbus.housekeeping.idempotency-retention", "6d")).close());

        assertTrue(messages(failure).contains(
                "Idempotency retention must cover Kafka topic retention and maximum redelivery window"));
    }

    private static ApplicationContext context(Map<String, Object> overrides) {
        var properties = new java.util.HashMap<String, Object>();
        properties.put("micronaut.server.enabled", false);
        properties.put("kafka.enabled", false);
        properties.put("micronaut.scheduling.enabled", false);
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.datasources.default.enabled", false);
        properties.putAll(overrides);
        return ApplicationContext.builder().properties(properties).start();
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }
}
