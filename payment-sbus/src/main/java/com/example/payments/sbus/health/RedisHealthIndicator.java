package com.example.payments.sbus.health;

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
 */
@Singleton
@Readiness
public class RedisHealthIndicator implements HealthIndicator {

    private static final String NAME = "redis";

    private final RedisCommandsProvider commands;

    public RedisHealthIndicator(RedisCommandsProvider commands) {
        this.commands = commands;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        HealthResult.Builder builder = HealthResult.builder(NAME);
        try {
            String pong = commands.commands().ping();
            builder.status("PONG".equalsIgnoreCase(pong) ? HealthStatus.UP : HealthStatus.DOWN);
        } catch (RuntimeException failure) {
            builder.status(HealthStatus.DOWN).exception(failure);
        }
        return Publishers.just(builder.build());
    }
}
