package com.example.platform.asyncredis.retention;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.queue.JobQueue;
import com.example.platform.asyncredis.redis.RedisConnections;
import com.example.platform.asyncredis.result.ResultReleaser;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-03 against the real Redis at {@code localhost:6379}: pending payload must survive retention
 * pressure, the backlog alert must fire before the safe budget runs out, and an incompatible Redis
 * version must never be treated as trim-capable. This sandbox's Redis is 7.0.15 - below the 8.2.0
 * ACKED minimum - so these tests also double as the "incompatible version fails safe" evidence: there
 * is no code path here that trims at all, on any version (see {@link StreamRetentionMonitor}).
 */
class StreamRetentionIT {

    private final String stream = "retention-it.jobs." + UUID.randomUUID();
    private RedisClient client;
    private StatefulRedisConnection<String, String> conn;

    @AfterEach
    void cleanup() {
        if (conn != null) {
            conn.sync().del(stream);
            conn.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    private AsyncRedisProperties propsFor(long maxlen, double alertThreshold) {
        AsyncRedisProperties props = new AsyncRedisProperties();
        props.setStream(stream);
        props.setStreamMaxlen(maxlen);
        props.setRetentionAlertThreshold(alertThreshold);
        return props;
    }

    private RedisConnections connect(AsyncRedisProperties props) {
        client = RedisClient.create("redis://localhost:6379");
        conn = client.connect();
        return new RedisConnections(client, props);
    }

    @Test
    void enqueueNeverTrimsSoAllPendingPayloadSurvivesPastMaxlen() {
        AsyncRedisProperties props = propsFor(3, 0.8);
        RedisConnections redis = connect(props);
        JobQueue queue = new JobQueue(redis, ObjectMapper.getDefault(), props,
                new ResultReleaser(redis, ObjectMapper.getDefault(), props));

        // No worker is attached to this stream, so every one of these stays pending/unconsumed.
        for (int i = 0; i < 6; i++) {
            queue.enqueue(UUID.randomUUID().toString(), new SubmitJobRequest("R-" + i, 100L, null));
        }

        assertEquals(6, conn.sync().xlen(stream),
                "stream-maxlen=3 must not silently drop any of the 6 pending entries");
    }

    @Test
    void checkNeverTrimsTheStreamRegardlessOfBacklog() {
        AsyncRedisProperties props = propsFor(2, 0.8);
        RedisConnections redis = connect(props);
        JobQueue queue = new JobQueue(redis, ObjectMapper.getDefault(), props,
                new ResultReleaser(redis, ObjectMapper.getDefault(), props));
        for (int i = 0; i < 5; i++) {
            queue.enqueue(UUID.randomUUID().toString(), new SubmitJobRequest("R-" + i, 100L, null));
        }
        StreamRetentionMonitor monitor = new StreamRetentionMonitor(redis, props);

        RetentionStatus status = monitor.check();

        assertEquals(5, status.streamLength(), "check() must observe the real length, unmodified");
        assertEquals(5, conn.sync().xlen(stream), "check() itself must never remove any entry");
    }

    @Test
    void checkAlertsOnceTheBacklogReachesTheSafeThreshold() {
        AsyncRedisProperties props = propsFor(10, 0.5); // alert threshold = 5
        RedisConnections redis = connect(props);
        JobQueue queue = new JobQueue(redis, ObjectMapper.getDefault(), props,
                new ResultReleaser(redis, ObjectMapper.getDefault(), props));
        for (int i = 0; i < 5; i++) {
            queue.enqueue(UUID.randomUUID().toString(), new SubmitJobRequest("R-" + i, 100L, null));
        }
        StreamRetentionMonitor monitor = new StreamRetentionMonitor(redis, props);

        RetentionStatus status = monitor.check();

        assertEquals(5, status.alertThreshold());
        assertTrue(status.backlogAlert(), "5 entries at a threshold of 5 must already alert");
    }

    @Test
    void checkDoesNotAlertBelowTheSafeThreshold() {
        AsyncRedisProperties props = propsFor(10, 0.5); // alert threshold = 5
        RedisConnections redis = connect(props);
        JobQueue queue = new JobQueue(redis, ObjectMapper.getDefault(), props,
                new ResultReleaser(redis, ObjectMapper.getDefault(), props));
        for (int i = 0; i < 4; i++) {
            queue.enqueue(UUID.randomUUID().toString(), new SubmitJobRequest("R-" + i, 100L, null));
        }
        StreamRetentionMonitor monitor = new StreamRetentionMonitor(redis, props);

        RetentionStatus status = monitor.check();

        assertFalse(status.backlogAlert(), "4 entries under a threshold of 5 must not alert yet");
    }

    @Test
    void checkReportsTheRealConnectedRedisServerVersion() {
        AsyncRedisProperties props = propsFor(10, 0.8);
        RedisConnections redis = connect(props);
        StreamRetentionMonitor monitor = new StreamRetentionMonitor(redis, props);
        String expected = versionFromRawInfo();

        RetentionStatus status = monitor.check();

        assertNotNull(status.serverVersion(), "the real server's version must be readable");
        assertEquals(expected, status.serverVersion());
        assertTrue(Pattern.matches("\\d+\\.\\d+\\.\\d+.*", status.serverVersion()),
                "must look like a Redis version; was " + status.serverVersion());
    }

    @Test
    void anIncompatibleServerVersionIsNeverReportedAsAckedTrimCapable() {
        AsyncRedisProperties props = propsFor(10, 0.8);
        RedisConnections redis = connect(props);
        StreamRetentionMonitor monitor = new StreamRetentionMonitor(redis, props);
        String actual = versionFromRawInfo();
        // This sandbox's Redis is 7.0.15, below the 8.2.0 ACKED minimum - a real, not simulated,
        // incompatible version.
        assertTrue(StreamRetentionMonitor.compareVersions(actual, "8.2.0") < 0,
                "test assumption: sandbox Redis must be older than 8.2.0; was " + actual);

        RetentionStatus status = monitor.check();

        assertFalse(status.ackedTrimSupported(),
                "an incompatible server must never be reported as ACKED-trim capable");
    }

    private String versionFromRawInfo() {
        RedisClient rawClient = RedisClient.create("redis://localhost:6379");
        try (StatefulRedisConnection<String, String> rawConn = rawClient.connect()) {
            String info = rawConn.sync().info("server");
            for (String line : info.split("\r\n")) {
                if (line.startsWith("redis_version:")) {
                    return line.substring("redis_version:".length()).trim();
                }
            }
            throw new IllegalStateException("redis_version not found in INFO server");
        } finally {
            rawClient.shutdown();
        }
    }
}
