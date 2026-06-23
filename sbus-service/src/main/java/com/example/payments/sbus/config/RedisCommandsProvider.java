package com.example.payments.sbus.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

/**
 * Lazily-connected Redis commands for the SBUS (used by the distributed rate limiter).
 * Lazy so the service boots even if Redis is briefly unavailable.
 */
@Singleton
public class RedisCommandsProvider {

    private final RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisCommandsProvider(RedisClient client) {
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
