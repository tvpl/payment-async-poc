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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Executes the declared PostgreSQL readiness budget (design §6, AUD-09): {@code SELECT 1}
 * bounded by {@code sbus.dependencies.postgresql.timeout}.
 *
 * <p>Deliberately bypasses the shared, pooled {@code DataSource}: a stale pooled connection left
 * over from before a real outage can hang a blocking socket read for far longer than any
 * {@code Statement#setQueryTimeout} — that only bounds query execution once a connection is
 * already in hand, not a dead socket read, and HikariCP's own {@code connectionTimeout} defaults
 * to 30s, well past this budget. A direct, short-lived {@link DriverManager} connection with the
 * PostgreSQL driver's own {@code connectTimeout}/{@code socketTimeout} properties set to the
 * budget bounds BOTH connecting and reading, so this check can never itself outlive its budget
 * — and it never touches the pool other business traffic depends on.
 */
@Singleton
@Readiness
public class PostgresHealthIndicator implements HealthIndicator {

    private static final String NAME = "postgresql";

    private final String url;
    private final String username;
    private final String password;
    private final DependencyPolicies policies;

    public PostgresHealthIndicator(
            @Value("${datasources.default.url}") String url,
            @Value("${datasources.default.username}") String username,
            @Value("${datasources.default.password}") String password,
            DependencyPolicies policies) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.policies = policies;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        DependencyPolicies.Budget budget = policies.budget(DependencyPolicies.Dependency.POSTGRESQL);
        int timeoutSeconds = Math.max(1, (int) budget.timeout().toSeconds());
        HealthResult.Builder builder = HealthResult.builder(NAME);
        try (Connection connection = DriverManager.getConnection(boundedUrl(timeoutSeconds), username, password)) {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.execute("SELECT 1");
            }
            builder.status(HealthStatus.UP);
        } catch (SQLException | RuntimeException failure) {
            builder.status(HealthStatus.DOWN).exception(failure);
        }
        return Publishers.just(builder.build());
    }

    private String boundedUrl(int timeoutSeconds) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "connectTimeout=" + timeoutSeconds + "&socketTimeout=" + timeoutSeconds;
    }
}
