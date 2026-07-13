package com.example.platform.featurecontrol.admin;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.store.FeatureRedisCommandsProvider;
import com.example.platform.featurecontrol.store.RedisFlagSource;
import io.lettuce.core.RedisClient;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

/**
 * Write path for runtime flag control: persists a {@link FlagDefinition} to Redis at
 * {@code <key-prefix><name>} so every instance of every app picks it up within one {@code cache-ttl}.
 * This is what lets an operator flip a toggle, change an A/B percentage, or add a user to the v0
 * allowlist without a deploy. Local cache is invalidated immediately so the writing instance sees
 * the change at once. Only wired when Redis is available.
 */
@Singleton
@Requires(beans = RedisClient.class)
public class FlagAdminService {

    private final FeatureRedisCommandsProvider redis;
    private final ObjectMapper objectMapper;
    private final FeatureSettings settings;
    @Nullable
    private final RedisFlagSource dynamicSource;

    public FlagAdminService(FeatureRedisCommandsProvider redis,
                            ObjectMapper objectMapper,
                            FeatureSettings settings,
                            @Nullable RedisFlagSource dynamicSource) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.dynamicSource = dynamicSource;
    }

    /** Upserts a flag definition in Redis and refreshes the local cache. */
    public void put(FlagDefinition definition) {
        try {
            String json = objectMapper.writeValueAsString(definition);
            redis.commands().set(settings.getKeyPrefix() + definition.name(), json);
            invalidate(definition.name());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist flag " + definition.name(), e);
        }
    }

    /** Removes the dynamic override so the YAML baseline applies again. */
    public void delete(String name) {
        redis.commands().del(settings.getKeyPrefix() + name);
        invalidate(name);
    }

    private void invalidate(String name) {
        if (dynamicSource != null) {
            dynamicSource.invalidate(name);
        }
    }
}
