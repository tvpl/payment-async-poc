package com.example.payments.sbus.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.exceptions.ConfigurationException;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** Bounded dependency behavior and the durable state that survives each outage. */
@Context
public final class DependencyPolicies {

    public enum Dependency { KAFKA, POSTGRESQL, REDIS, SCHEMA_REGISTRY }

    public enum RecoverableState {
        KAFKA_RECORD,
        CLAIMED_OUTBOX
    }

    public record Budget(Duration timeout, int maxAttempts, boolean requiredForReadiness,
                         RecoverableState recoverableState) {
    }

    private final Map<Dependency, Budget> budgets;

    public DependencyPolicies(
            @Value("${sbus.dependencies.kafka.timeout:30s}") Duration kafkaTimeout,
            @Value("${sbus.dependencies.kafka.max-attempts:8}") int kafkaAttempts,
            @Value("${sbus.dependencies.kafka.readiness-required:true}") boolean kafkaReadiness,
            @Value("${sbus.dependencies.postgresql.timeout:3s}") Duration postgresTimeout,
            @Value("${sbus.dependencies.postgresql.max-attempts:3}") int postgresAttempts,
            @Value("${sbus.dependencies.postgresql.readiness-required:true}") boolean postgresReadiness,
            @Value("${sbus.dependencies.redis.timeout:2s}") Duration redisTimeout,
            @Value("${sbus.dependencies.redis.max-attempts:1}") int redisAttempts,
            // RES-03/RES-04: Redis backs only the Core rate limiter, not payment durability —
            // an outage degrades throttling, it does not make the SBUS instance unable to do
            // useful work, so it defaults to NOT gating readiness (see RedisHealthIndicator).
            @Value("${sbus.dependencies.redis.readiness-required:false}") boolean redisReadiness,
            @Value("${sbus.dependencies.registry.timeout:3s}") Duration registryTimeout,
            @Value("${sbus.dependencies.registry.max-attempts:3}") int registryAttempts,
            @Value("${sbus.dependencies.registry.readiness-required:true}") boolean registryReadiness) {
        budgets = validated(Map.of(
                Dependency.KAFKA, new Budget(kafkaTimeout, kafkaAttempts, kafkaReadiness,
                        RecoverableState.CLAIMED_OUTBOX),
                Dependency.POSTGRESQL, new Budget(postgresTimeout, postgresAttempts, postgresReadiness,
                        RecoverableState.KAFKA_RECORD),
                Dependency.REDIS, new Budget(redisTimeout, redisAttempts, redisReadiness,
                        RecoverableState.CLAIMED_OUTBOX),
                Dependency.SCHEMA_REGISTRY, new Budget(registryTimeout, registryAttempts, registryReadiness,
                        RecoverableState.KAFKA_RECORD)));
    }

    public Budget budget(Dependency dependency) {
        return budgets.get(dependency);
    }

    public Map<Dependency, Budget> all() {
        return budgets;
    }

    static Map<Dependency, Budget> validated(Map<Dependency, Budget> candidates) {
        EnumMap<Dependency, Budget> result = new EnumMap<>(Dependency.class);
        for (Dependency dependency : Dependency.values()) {
            Budget budget = candidates.get(dependency);
            if (budget == null) {
                throw new ConfigurationException("Missing dependency policy for " + dependency);
            }
            if (budget.timeout() == null || budget.timeout().isZero() || budget.timeout().isNegative()) {
                throw new ConfigurationException(dependency + " timeout must be positive");
            }
            if (budget.maxAttempts() < 1) {
                throw new ConfigurationException(dependency + " max-attempts must be at least 1");
            }
            // RES-04: readiness-required:false is now an accepted, deliberate policy for a
            // non-critical dependency (see RedisHealthIndicator) — no longer rejected here.
            result.put(dependency, budget);
        }
        return Map.copyOf(result);
    }
}
