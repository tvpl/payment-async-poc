package com.example.payments.api.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

/** Lazily-connected Redis commands for the API's distributed admission rate limiter. */
@Singleton
public class ApiRedisCommandsProvider {

    private final RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;

    public ApiRedisCommandsProvider(RedisClient client) {
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
