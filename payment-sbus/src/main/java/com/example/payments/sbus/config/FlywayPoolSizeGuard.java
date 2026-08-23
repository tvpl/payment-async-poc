package com.example.payments.sbus.config;

import io.micronaut.configuration.jdbc.hikari.DatasourceConfiguration;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.inject.Singleton;

/**
 * Fails startup immediately, with a message that names the actual cause, when a Flyway-managed
 * datasource's connection pool is too small for Flyway to ever migrate.
 *
 * <p>{@code micronaut-flyway} migrates from a {@code BeanCreatedEventListener} on the
 * {@code DataSource} bean itself, and Flyway's own {@code DbMigrate} opens a SECOND physical
 * connection ({@code Database.getMigrationConnection()}) while its main schema-history connection
 * is still checked out. A pool of 1 makes Flyway wait on itself for the full
 * {@code connection-timeout} (default 30s) and the whole application context then fails to start
 * with a bare {@code HikariPool - Connection is not available} timeout that never mentions Flyway
 * — the exact trap {@code HikariPoolHealthIndicatorIT} hit in the test tree before this session
 * (see that class's own javadoc for the root-cause writeup). This guard closes the equivalent gap
 * in application config: a misconfigured {@code maximum-pool-size} now fails fast, in one line
 * that names Flyway, instead of a 30-second hang and a misleading Hikari-only stack trace.
 *
 * <p>Intercepts {@link DatasourceConfiguration}, not the {@code DataSource} bean itself:
 * {@code DatasourceConfiguration} is a required constructor argument of the factory method that
 * builds the actual pool ({@code DatasourceFactory.dataSource(DatasourceConfiguration)}), so
 * Micronaut's dependency graph guarantees this listener runs before that pool — and therefore
 * before Flyway — is ever created. That guarantee comes from the constructor-dependency edge, not
 * from eager-singleton creation order (which Micronaut does not otherwise promise between
 * unrelated beans), so it holds regardless of what else this application wires up. It also means
 * the check never touches the database: a misconfigured pool is rejected before any connection is
 * even attempted.
 */
@Singleton
public class FlywayPoolSizeGuard implements BeanCreatedEventListener<DatasourceConfiguration> {

    static final int MINIMUM_POOL_SIZE_FOR_FLYWAY = 2;

    private final Environment environment;

    public FlywayPoolSizeGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public DatasourceConfiguration onCreated(BeanCreatedEvent<DatasourceConfiguration> event) {
        DatasourceConfiguration configuration = event.getBean();
        String name = configuration.getName();
        boolean flywayEnabledForThisDatasource = environment.getProperty(
                "flyway.datasources." + name + ".enabled", Boolean.class, true);
        validate(name, configuration.getMaximumPoolSize(), flywayEnabledForThisDatasource);
        return configuration;
    }

    static void validate(String datasourceName, int maximumPoolSize, boolean flywayEnabled) {
        if (flywayEnabled && maximumPoolSize < MINIMUM_POOL_SIZE_FOR_FLYWAY) {
            throw new ConfigurationException(
                    "datasources." + datasourceName + ".maximum-pool-size is " + maximumPoolSize
                            + ", but Flyway is enabled for this datasource and needs at least "
                            + MINIMUM_POOL_SIZE_FOR_FLYWAY
                            + ": it opens a second connection mid-migration"
                            + " (Database.getMigrationConnection()) while the schema-history"
                            + " connection is still checked out, so a smaller pool self-deadlocks for"
                            + " the full connection-timeout instead of failing clearly.");
        }
    }
}
