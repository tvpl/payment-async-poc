package com.example.platform.asyncredis.worker;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-04: a consumer name identifies one worker of one instance. Redis keys a consumer group's
 * pending list by that name, so two processes sharing it are treated as a single consumer — each
 * inherits the other's in-flight entries.
 */
class WorkerIdentityUnitTest {

    private static WorkerIdentity identity(String configuredInstanceId) {
        AsyncRedisProperties props = new AsyncRedisProperties();
        props.setInstanceId(configuredInstanceId);
        return new WorkerIdentity(props);
    }

    @Test
    void workersOfOneInstanceNeverShareAConsumerName() {
        WorkerIdentity identity = identity(null);

        Set<String> names = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            names.add(identity.consumerName(i));
        }

        assertEquals(8, names.size(), "eight workers must produce eight names; got " + names);
    }

    @Test
    void separateInstancesNeverShareAConsumerName() {
        // Two processes of the same deployment, same worker index: the collision RED-04 forbids.
        WorkerIdentity first = identity(null);
        WorkerIdentity second = identity(null);

        assertNotEquals(first.instanceId(), second.instanceId());
        assertNotEquals(first.consumerName(0), second.consumerName(0));
        assertNotEquals(first.consumerName(1), second.consumerName(1));
    }

    @Test
    void aConsumerNameIsStableForTheLifeOfTheWorker() {
        // A name regenerated per poll would strand every entry already delivered to the old name.
        WorkerIdentity identity = identity(null);

        assertEquals(identity.consumerName(0), identity.consumerName(0));
        assertEquals(identity.instanceId(), identity.instanceId());
    }

    @Test
    void anExplicitInstanceIdIsUsedVerbatim() {
        WorkerIdentity identity = identity("pod-7");

        assertEquals("pod-7", identity.instanceId());
        assertEquals("pod-7-w0", identity.consumerName(0));
        assertEquals("pod-7-w3", identity.consumerName(3));
    }

    @Test
    void aBlankInstanceIdFallsBackToADerivedOne() {
        WorkerIdentity identity = identity("   ");

        assertTrue(identity.instanceId().length() > 1, "blank config must not become a blank identity");
        assertNotEquals(identity.instanceId(), identity("   ").instanceId());
    }
}
