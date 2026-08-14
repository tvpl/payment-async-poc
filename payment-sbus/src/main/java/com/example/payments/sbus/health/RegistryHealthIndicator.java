package com.example.payments.sbus.health;

import com.example.payments.sbus.config.DependencyPolicies;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.management.health.indicator.annotation.Readiness;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Executes the declared Apicurio Schema Registry readiness budget (design §6, AUD-09):
 * {@code GET /system/info} bounded by {@code sbus.dependencies.registry.timeout}. Uses the JDK
 * HTTP client directly (no new dependency) — this is the one dependency SBUS never checked at
 * all before, and its outage used to masquerade as a poison-message bug instead of a readiness
 * one (see {@code SimulationMessageHandler}'s connectivity-vs-decode split, same requirement).
 */
@Singleton
@Readiness
public class RegistryHealthIndicator implements HealthIndicator {

    private static final String NAME = "registry";

    private final HttpClient httpClient;
    private final URI systemInfoUri;
    private final DependencyPolicies policies;

    public RegistryHealthIndicator(
            @Value("${apicurio.registry.url:`http://localhost:8085/apis/registry/v2`}") String registryUrl,
            DependencyPolicies policies) {
        this.policies = policies;
        String base = registryUrl.endsWith("/") ? registryUrl.substring(0, registryUrl.length() - 1) : registryUrl;
        this.systemInfoUri = URI.create(base + "/system/info");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(policies.budget(DependencyPolicies.Dependency.SCHEMA_REGISTRY).timeout())
                .build();
    }

    @Override
    public Publisher<HealthResult> getResult() {
        DependencyPolicies.Budget budget = policies.budget(DependencyPolicies.Dependency.SCHEMA_REGISTRY);
        HealthResult.Builder builder = HealthResult.builder(NAME);
        try {
            HttpRequest request = HttpRequest.newBuilder(systemInfoUri)
                    .timeout(budget.timeout())
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            boolean up = response.statusCode() >= 200 && response.statusCode() < 300;
            builder.status(up ? HealthStatus.UP : HealthStatus.DOWN)
                    .details(Map.of("statusCode", response.statusCode()));
        } catch (java.io.IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            builder.status(HealthStatus.DOWN).exception(failure);
        }
        return Publishers.just(builder.build());
    }
}
