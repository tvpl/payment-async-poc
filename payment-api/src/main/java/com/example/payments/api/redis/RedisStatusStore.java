package com.example.payments.api.redis;

import com.example.payments.api.config.ApiProperties;
import com.example.payments.api.dto.StatusEntry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.serde.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Redis-backed shared state for the API: request status/result and the
 * idempotencyKey -&gt; requestId mapping, so correlation and de-duplication work
 * across instances.
 *
 * <p>The connection is obtained <strong>lazily</strong> from {@link RedisClient} (and
 * re-established if dropped), so the application boots even when Redis is briefly
 * unavailable instead of crashing at startup.
 */
@Singleton
public class RedisStatusStore {

    private static final Logger LOG = LoggerFactory.getLogger(RedisStatusStore.class);
    private static final String STATUS_PREFIX = "payment-simulation:";
    private static final String IDEM_PREFIX = "idem:";

    private final RedisClient redisClient;
    private final ObjectMapper objectMapper;
    private final ApiProperties properties;

    private volatile StatefulRedisConnection<String, String> connection;

    public RedisStatusStore(RedisClient redisClient,
                            ObjectMapper objectMapper,
                            ApiProperties properties) {
        this.redisClient = redisClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    private RedisCommands<String, String> commands() {
        StatefulRedisConnection<String, String> conn = connection;
        if (conn == null || !conn.isOpen()) {
            synchronized (this) {
                if (connection == null || !connection.isOpen()) {
                    connection = redisClient.connect();
                }
            }
        }
        return connection.sync();
    }

    public void save(StatusEntry entry) {
        try {
            String json = objectMapper.writeValueAsString(entry);
            commands().set(statusKey(entry.requestId()), json,
                    SetArgs.Builder.px(properties.getStatusTtl().toMillis()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save status for " + entry.requestId(), e);
        }
    }

    public Optional<StatusEntry> get(String requestId) {
        String json = commands().get(statusKey(requestId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, StatusEntry.class));
        } catch (Exception e) {
            LOG.error("Failed to read status entry for {}", requestId, e);
            return Optional.empty();
        }
    }

    /**
     * Reserves an idempotency key for this requestId.
     *
     * @return empty if reserved by us now; otherwise the requestId that already owns it.
     */
    public Optional<String> reserveIdempotency(String idempotencyKey, String requestId) {
        String key = IDEM_PREFIX + idempotencyKey;
        String result = commands().set(key, requestId,
                SetArgs.Builder.nx().px(properties.getIdempotencyTtl().toMillis()));
        if ("OK".equals(result)) {
            return Optional.empty();
        }
        return Optional.ofNullable(commands().get(key));
    }

    public void publishResponse(String requestId) {
        commands().publish(properties.getResponseChannel(), requestId);
    }

    @PreDestroy
    void close() {
        if (connection != null) {
            connection.close();
        }
    }

    private static String statusKey(String requestId) {
        return STATUS_PREFIX + requestId;
    }
}
