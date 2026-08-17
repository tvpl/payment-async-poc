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
 *
 * <p>SCAL-04: the connection borrowed to hold the advisory lock is now closed via
 * try-with-resources on every path (success, throw, or the lock simply not being available) —
 * it used to never be returned to the pool at all, leaking one Hikari connection per call. The
 * lock key is also namespaced: {@code pg_try_advisory_lock(classid, objid)} with a dedicated
 * {@link #LOCK_CLASSID}, instead of the single-argument form keyed on the outbox row id alone —
 * that form shares ONE flat 64-bit key space process-wide, so any other code in this same
 * database that happens to pick the same numeric key (a row id from an unrelated table, for
 * instance) would collide with this lock for no reason. The two-argument form's {@code objid} is
 * a 32-bit int (see {@link #objid}), which the outbox table's {@code BIGINT} identity column
 * will not exceed for a very long time — its retention window (see {@code RetentionHousekeeping})
 * keeps it from ever accumulating anywhere near that.
 */
@Singleton
public class OutboxPublicationLock {

    /**
     * Dedicated classid namespacing this lock's key space from any OTHER use of Postgres advisory
     * locks in the same database — arbitrarily chosen but fixed and documented, so a collision
     * with an unrelated advisory lock elsewhere is structurally impossible, not just unlikely.
     */
    static final int LOCK_CLASSID = 0x5B05;

    private final DataSource dataSource;

    public OutboxPublicationLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Connectable
    public <T> Optional<T> executeIfAcquired(long outboxId, Supplier<T> action) {
        int objid = objid(outboxId);
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
                statement.setInt(1, LOCK_CLASSID);
                statement.setInt(2, objid);
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
                try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
                    statement.setInt(1, LOCK_CLASSID);
                    statement.setInt(2, objid);
                    statement.execute();
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to operate outbox publication lock " + outboxId,
                    exception);
        }
    }

    private static int objid(long outboxId) {
        return (int) outboxId;
    }
}
