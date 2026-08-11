package com.example.payments.api.redis;

import com.example.payments.api.coordination.ResponseCoordinator;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.TimeoutOptions;
import io.micronaut.context.annotation.Context;
import jakarta.inject.Singleton;

import java.time.Duration;

/**
 * Bounds every Lettuce command's timeout well under the client's 60s default (task_3801253b):
 * under sustained load, {@link RedisStatusStore}'s single connection could back up until a
 * command sat for the full 60s before failing, and because {@link ResponseCoordinator} calls
 * that store from inside the Redis PubSub listener thread, one such stall blocked delivery of
 * every other instance's completion notifications too.
 *
 * <p>{@code @Context} forces eager initialization at application startup, before any other
 * bean's lazy {@code redisClient.connect()}/{@code connectPubSub()} call — {@link ClientOptions}
 * apply only to connections created after {@link RedisClient#setOptions} runs.
 */
@Context
@Singleton
public class RedisClientTuning {

    public RedisClientTuning(RedisClient redisClient) {
        redisClient.setOptions(ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
                .build());
    }
}
