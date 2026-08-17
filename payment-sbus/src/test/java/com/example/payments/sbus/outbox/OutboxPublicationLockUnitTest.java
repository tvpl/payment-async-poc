package com.example.payments.sbus.outbox;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task_T41 (SCAL-04): deterministic, mock-driven proof that {@link OutboxPublicationLock} closes
 * its borrowed connection on every path — the exact leak the pre-fix code had, which
 * {@code OutboxPublicationLockConnectionLeakIT} proves at the pool-metrics level under real
 * concurrency; this pins down each individual path (lock acquired, lock denied, action throws)
 * without Postgres or timing involved. Also proves the lock key uses the dedicated,
 * two-argument {@code pg_try_advisory_lock(classid, objid)} form, not the single-argument one.
 */
class OutboxPublicationLockUnitTest {

    @Test
    void closesTheConnectionAfterANormalAcquireRunAndRelease() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement lockStatement = mock(PreparedStatement.class);
        PreparedStatement unlockStatement = mock(PreparedStatement.class);
        ResultSet lockResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(lockStatement);
        when(connection.prepareStatement(contains("pg_advisory_unlock"))).thenReturn(unlockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(true);

        OutboxPublicationLock lock = new OutboxPublicationLock(dataSource);
        Optional<String> result = lock.executeIfAcquired(42L, () -> "done");

        assertEquals(Optional.of("done"), result);
        verify(lockStatement).setInt(1, OutboxPublicationLock.LOCK_CLASSID);
        verify(lockStatement).setInt(2, 42);
        verify(unlockStatement).setInt(1, OutboxPublicationLock.LOCK_CLASSID);
        verify(unlockStatement).setInt(2, 42);
        verify(unlockStatement).execute();
        verify(connection).close();
    }

    @Test
    void closesTheConnectionWhenTheLockIsNotAcquiredWithoutEverUnlocking() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement lockStatement = mock(PreparedStatement.class);
        ResultSet lockResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(lockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(false);

        OutboxPublicationLock lock = new OutboxPublicationLock(dataSource);
        boolean[] actionRan = {false};
        Optional<String> result = lock.executeIfAcquired(7L, () -> {
            actionRan[0] = true;
            return "should never run";
        });

        assertTrue(result.isEmpty());
        assertTrue(!actionRan[0], "the action must never run when the lock was not acquired");
        verify(connection, never()).prepareStatement(contains("pg_advisory_unlock"));
        verify(connection).close();
    }

    @Test
    void closesTheConnectionEvenWhenTheActionThrows() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement lockStatement = mock(PreparedStatement.class);
        PreparedStatement unlockStatement = mock(PreparedStatement.class);
        ResultSet lockResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(lockStatement);
        when(connection.prepareStatement(contains("pg_advisory_unlock"))).thenReturn(unlockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(true);

        OutboxPublicationLock lock = new OutboxPublicationLock(dataSource);
        RuntimeException failure = new RuntimeException("boom");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> lock.executeIfAcquired(1L, () -> {
            throw failure;
        }));

        assertEquals(failure, thrown, "the action's own exception must propagate unwrapped");
        verify(unlockStatement, times(1)).execute();
        verify(connection).close();
    }
}
