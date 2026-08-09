package com.example.payments.sbus.outbox;

import io.micronaut.data.connection.annotation.Connectable;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PostgreSQL session lock held across the broker send. Unlike a row lock, this
 * survives the short claim transaction and prevents a reclaimed claim from
 * publishing concurrently with its still-running former owner.
 */
@Singleton
public class OutboxPublicationLock {

    private final DataSource dataSource;

    public OutboxPublicationLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Connectable
    public <T> Optional<T> executeIfAcquired(long outboxId, Supplier<T> action) {
        try {
            var connection = dataSource.getConnection();
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, outboxId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) {
                        return Optional.empty();
                    }
                }
            }
            try {
                return Optional.ofNullable(action.get());
            } finally {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    statement.setLong(1, outboxId);
                    statement.execute();
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to operate outbox publication lock " + outboxId,
                    exception);
        }
    }
}
