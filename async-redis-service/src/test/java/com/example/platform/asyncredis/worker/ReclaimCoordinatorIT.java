package com.example.platform.asyncredis.worker;

import com.example.platform.asyncredis.api.JobKeys;
import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-04: exactly one worker scans and reclaims pending entries at a time. Concurrent scanners race
 * to {@code XCLAIM} the same ids, so one entry is redelivered to several workers and its delivery
 * count climbs toward the DLQ on redeliveries no failure caused.
 *
 * <p>Runs against the real Redis at {@code localhost:6379} and builds the coordinator directly, so no
 * live worker competes for the turn under test.
 */
class ReclaimCoordinatorIT {

    private static final String GROUP = "reclaim-it-" + UUID.randomUUID();
    private static final Duration LEASE = Duration.ofMillis(600);
    private static final Duration INTERVAL = Duration.ofMillis(100);

    private static RedisClient client;
    private static RedisConnections connections;
    private static AsyncRedisProperties props;
    private ReclaimCoordinator coordinator;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://localhost:6379");
        props = new AsyncRedisProperties();
        props.setGroup(GROUP);
        props.setReclaimLease(LEASE);
        props.setReclaimInterval(INTERVAL);
        connections = new RedisConnections(client, props);
    }

    @AfterAll
    static void disconnect() {
        connections.shared().del(JobKeys.reclaimLease(GROUP));
        client.shutdown();
    }

    @BeforeEach
    void clearLease() {
        connections.shared().del(JobKeys.reclaimLease(GROUP));
        coordinator = new ReclaimCoordinator(connections, props);
    }

    @Test
    void onlyOneWorkerHoldsTheReclaimTurnAtATime() {
        assertTrue(coordinator.claimTurn("worker-a"), "the first worker must get the turn");

        assertFalse(coordinator.claimTurn("worker-b"), "a second worker must not scan concurrently");
        assertEquals("worker-a", coordinator.currentOwner());
    }

    @Test
    void theOwnerKeepsItsTurnAcrossScans() {
        assertTrue(coordinator.claimTurn("worker-a"));

        // Renewal, not re-acquisition: a SET NX alone would hand the turn away every cycle.
        assertTrue(coordinator.claimTurn("worker-a"), "the owner must keep scanning across cycles");
        assertEquals("worker-a", coordinator.currentOwner());
        assertFalse(coordinator.claimTurn("worker-b"));
    }

    @Test
    void aReleasedTurnPassesToTheNextWorker() {
        assertTrue(coordinator.claimTurn("worker-a"));

        coordinator.releaseTurn("worker-a");

        assertNull(coordinator.currentOwner(), "a released turn must be free");
        assertTrue(coordinator.claimTurn("worker-b"), "the next worker must be able to take it");
        assertEquals("worker-b", coordinator.currentOwner());
    }

    @Test
    void aNonOwnerCannotReleaseSomeoneElsesTurn() {
        assertTrue(coordinator.claimTurn("worker-a"));

        // Fencing: a lapsed or confused worker must not evict the current owner.
        coordinator.releaseTurn("worker-b");

        assertEquals("worker-a", coordinator.currentOwner());
        assertFalse(coordinator.claimTurn("worker-b"));
    }

    @Test
    void aCrashedOwnersTurnExpiresSoReclaimResumes() throws Exception {
        assertTrue(coordinator.claimTurn("worker-a"));

        // worker-a "crashes": it never renews. The lease must free itself rather than stall reclaim.
        Thread.sleep(LEASE.toMillis() + 300);

        assertTrue(coordinator.claimTurn("worker-b"), "an expired lease must not block reclaim forever");
        assertEquals("worker-b", coordinator.currentOwner());
    }

    @Test
    void aLapsedOwnerCannotResurrectItsTurnOverTheNewOwner() throws Exception {
        assertTrue(coordinator.claimTurn("worker-a"));
        Thread.sleep(LEASE.toMillis() + 300);
        assertTrue(coordinator.claimTurn("worker-b"));

        // worker-a comes back and tries to renew a lease it no longer holds.
        assertFalse(coordinator.claimTurn("worker-a"), "a lapsed owner must not take the turn back");
        assertEquals("worker-b", coordinator.currentOwner());
    }
}
