package com.example.platform.featurecontrol.store;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

/**
 * Lazily-connected Redis commands for the dynamic flag store. Only active when a {@link RedisClient}
 * bean exists (an app on {@code micronaut-redis-lettuce}); apps without Redis simply run YAML-only.
 * Mirrors the connection handling in the API's own provider so the lib boots even if Redis is
 * briefly unavailable at startup.
 */
@Singleton
@Requires(beans = RedisClient.class)
public class FeatureRedisCommandsProvider {

    private final RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;

    public FeatureRedisCommandsProvider(RedisClient client) {
        this.client = client;
    }

    public RedisCommands<String, String> commands() {
        StatefulRedisConnection<String, String> conn = connection;
        if (conn == null || !conn.isOpen()) {
            synchronized (this) {
                if (connection == null || !connection.isOpen()) {
                    connection = client.connect();
                }
            }
        }
        return connection.sync();
    }

    @PreDestroy
    void close() {
        if (connection != null) {
            connection.close();
        }
    }
}
