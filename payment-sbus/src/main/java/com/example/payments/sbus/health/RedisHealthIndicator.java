package com.example.payments.sbus.health;

import com.example.payments.sbus.config.DependencyPolicies;
import com.example.payments.sbus.config.RedisCommandsProvider;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.management.health.indicator.annotation.Readiness;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

/**
 * Executes the declared Redis readiness budget (design §6, AUD-09): {@code PING} bounded by
 * {@code sbus.dependencies.redis.timeout} — {@link RedisCommandsProvider} already applies that
 * exact timeout to its connection, so this reuses the same bounded client instead of opening a
 * second one.
 *
 * <p>{@code micronaut-redis-lettuce} ships its own built-in {@code redis}-named indicator, which
 * does not read {@link com.example.payments.sbus.config.DependencyPolicies} — disabled via
 * {@code redis.health.enabled: false} in application.yml so this one (wired to the declared
 * budget) is the only "redis" entry in the readiness response.
 *
 * <p>RES-03/RES-04: Redis backs only the Core rate limiter, not payment durability. With
 * {@code sbus.dependencies.redis.readiness-required} at its default of {@code false}, a Redis
 * outage still shows up here as {@link #DEGRADED} — an operational status, so it does not sink
 * the readiness aggregate — instead of the plain {@link HealthStatus#DOWN} it reports when an
 * operator has explicitly opted this dependency back into gating readiness.
 */
@Singleton
@Readiness
public class RedisHealthIndicator implements HealthIndicator {

    private static final String NAME = "redis";

    /** Non-critical-dependency-down: operational (does not sink the readiness aggregate). */
    private static final HealthStatus DEGRADED = new HealthStatus("DEGRADED",
            "redis unreachable but not required for SBUS readiness (RES-03)", Boolean.TRUE, null);

    private final RedisCommandsProvider commands;
    private final DependencyPolicies policies;

    public RedisHealthIndicator(RedisCommandsProvider commands, DependencyPolicies policies) {
        this.commands = commands;
        this.policies = policies;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        HealthResult.Builder builder = HealthResult.builder(NAME);
        try {
            String pong = commands.commands().ping();
            builder.status("PONG".equalsIgnoreCase(pong) ? HealthStatus.UP : downOrDegraded());
        } catch (RuntimeException failure) {
            builder.status(downOrDegraded()).exception(failure);
        }
        return Publishers.just(builder.build());
    }

    private HealthStatus downOrDegraded() {
        boolean requiredForReadiness = policies.budget(DependencyPolicies.Dependency.REDIS).requiredForReadiness();
        return requiredForReadiness ? HealthStatus.DOWN : DEGRADED;
    }
}
