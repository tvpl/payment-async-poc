package com.example.payments.sbus;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TEN-06: proves the V12 migration two things a raw SQL read can't fake — (1) a database already
 * on V11 with pre-existing rows upgrades cleanly, attributing every old row to the synthetic
 * {@code legacy} tenant; (2) the unique constraint it installs is composite, never global — the
 * same {@code idempotency_key} can belong to two different tenants, but not to the same tenant
 * twice.
 *
 * <p>Runs Flyway directly against the container (not through the Micronaut application context):
 * the point under test is the migration script itself, staged at exactly the V11 boundary before
 * upgrading, which the app's own auto-migrate-on-boot never does.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantScopeMigrationIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @BeforeAll
    void start() {
        POSTGRES.start();
    }

    @AfterAll
    void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    @Test
    void aDatabaseOnV11WithExistingRowsUpgradesToV12WithEveryOldRowAttributedToTheLegacyTenant() throws Exception {
        migrateTo("11");

        String idempotencyKey = "idem-" + UUID.randomUUID();
        String requestId = UUID.randomUUID().toString();
        try (Connection connection = connect()) {
            insertPreV12IdempotencyRecord(connection, idempotencyKey, requestId);
            insertPreV12Message(connection, requestId, idempotencyKey);
        }

        migrateToLatest();

        try (Connection connection = connect()) {
            assertEquals("legacy", readTenantId(connection,
                    "SELECT tenant_id FROM idempotency_record WHERE idempotency_key = ?", idempotencyKey));
            assertEquals("legacy", readTenantId(connection,
                    "SELECT tenant_id FROM payment_sbus_message WHERE request_id = ?", requestId));
        }
    }

    @Test
    void theSameIdempotencyKeyInsertsIndependentlyForTwoDistinctTenantsButNotTwiceForTheSameTenant() throws Exception {
        migrateToLatest();

        String idempotencyKey = "idem-" + UUID.randomUUID();
        try (Connection connection = connect()) {
            insertIdempotencyRecord(connection, "tenant-a", idempotencyKey, UUID.randomUUID().toString());
            insertIdempotencyRecord(connection, "tenant-b", idempotencyKey, UUID.randomUUID().toString());

            assertEquals(2, countIdempotencyRecords(connection, idempotencyKey),
                    "the same key must insert independently for two distinct tenants — the "
                            + "constraint is composite, never global");

            assertThrows(SQLException.class,
                    () -> insertIdempotencyRecord(connection, "tenant-a", idempotencyKey, UUID.randomUUID().toString()),
                    "a second row for the SAME tenant and the SAME key must violate the composite "
                            + "unique constraint");
        }
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private void migrateToLatest() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.LATEST)
                .load()
                .migrate();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void insertPreV12IdempotencyRecord(Connection connection, String idempotencyKey,
                                                       String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO idempotency_record (idempotency_key, request_id, status) VALUES (?, ?, 'PROCESSING')")) {
            statement.setString(1, idempotencyKey);
            statement.setString(2, requestId);
            statement.executeUpdate();
        }
    }

    private static void insertPreV12Message(Connection connection, String requestId, String idempotencyKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO payment_sbus_message (request_id, idempotency_key, status, payload) "
                        + "VALUES (?, ?, 'PROCESSING', '{}'::jsonb)")) {
            statement.setString(1, requestId);
            statement.setString(2, idempotencyKey);
            statement.executeUpdate();
        }
    }

    private static void insertIdempotencyRecord(Connection connection, String tenantId, String idempotencyKey,
                                                String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO idempotency_record (tenant_id, idempotency_key, request_id, status) "
                        + "VALUES (?, ?, ?, 'PROCESSING')")) {
            statement.setString(1, tenantId);
            statement.setString(2, idempotencyKey);
            statement.setString(3, requestId);
            statement.executeUpdate();
        }
    }

    private static String readTenantId(Connection connection, String sql, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static int countIdempotencyRecords(Connection connection, String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM idempotency_record WHERE idempotency_key = ?")) {
            statement.setString(1, idempotencyKey);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
