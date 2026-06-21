package com.example.payments.api.redis;

import com.example.payments.api.config.ApiProperties;
import com.example.payments.api.dto.StatusEntry;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Redis-backed shared state for the API. Keeps request status/result and the
 * idempotencyKey -&gt; requestId mapping so correlation and de-duplication work
 * across multiple API instances (not just in local memory).
 */
@Singleton
public class RedisStatusStore {

    private static final Logger LOG = LoggerFactory.getLogger(RedisStatusStore.class);
    private static final String STATUS_PREFIX = "payment-simulation:";
    private static final String IDEM_PREFIX = "idem:";

    private final RedisCommands<String, String> commands;
    private final ObjectMapper objectMapper;
    private final ApiProperties properties;

    public RedisStatusStore(StatefulRedisConnection<String, String> connection,
                            ObjectMapper objectMapper,
                            ApiProperties properties) {
        this.commands = connection.sync();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void save(StatusEntry entry) {
        try {
            String json = objectMapper.writeValueAsString(entry);
            commands.set(statusKey(entry.requestId()), json,
                    SetArgs.Builder.px(properties.getStatusTtl().toMillis()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save status for " + entry.requestId(), e);
        }
    }

    public Optional<StatusEntry> get(String requestId) {
        String json = commands.get(statusKey(requestId));
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
        String result = commands.set(key, requestId,
                SetArgs.Builder.nx().px(properties.getIdempotencyTtl().toMillis()));
        if ("OK".equals(result)) {
            return Optional.empty();
        }
        return Optional.ofNullable(commands.get(key));
    }

    public void publishResponse(String requestId) {
        commands.publish(properties.getResponseChannel(), requestId);
    }

    private static String statusKey(String requestId) {
        return STATUS_PREFIX + requestId;
    }
}
