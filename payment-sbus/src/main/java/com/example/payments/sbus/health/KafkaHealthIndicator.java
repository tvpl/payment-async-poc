package com.example.payments.sbus.health;

import com.example.payments.sbus.config.DependencyPolicies;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.management.health.indicator.annotation.Readiness;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.reactivestreams.Publisher;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes the declared Kafka readiness budget (design §6, AUD-09): {@code describeCluster}
 * bounded by {@code sbus.dependencies.kafka.timeout}. {@code readiness-required: true} was
 * declared in {@link DependencyPolicies} but nothing ever actually checked it — this makes the
 * declaration real.
 *
 * <p>{@code micronaut-kafka} ships its own built-in {@code kafka}-named indicator, which does
 * not read {@link DependencyPolicies} — disabled via {@code kafka.health.enabled: false} in
 * application.yml so this one (wired to the declared budget) is the only "kafka" entry in the
 * readiness response.
 */
@Singleton
@Readiness
public class KafkaHealthIndicator implements HealthIndicator {

    private static final String NAME = "kafka";

    private final AdminClient adminClient;
    private final DependencyPolicies policies;

    public KafkaHealthIndicator(AdminClient adminClient, DependencyPolicies policies) {
        this.adminClient = adminClient;
        this.policies = policies;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        DependencyPolicies.Budget budget = policies.budget(DependencyPolicies.Dependency.KAFKA);
        HealthResult.Builder builder = HealthResult.builder(NAME);
        try {
            DescribeClusterOptions options =
                    new DescribeClusterOptions().timeoutMs((int) budget.timeout().toMillis());
            String clusterId = adminClient.describeCluster(options).clusterId()
                    .get(budget.timeout().toMillis(), TimeUnit.MILLISECONDS);
            builder.status(HealthStatus.UP).details(Map.of("clusterId", clusterId));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            builder.status(HealthStatus.DOWN).exception(interrupted);
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            builder.status(HealthStatus.DOWN).exception(failure);
        }
        return Publishers.just(builder.build());
    }
}
