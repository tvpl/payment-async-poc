package com.example.platform.featurecontrol.admin;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.store.FeatureRedisCommandsProvider;
import com.example.platform.featurecontrol.store.FlagChangeNotifier;
import com.example.platform.featurecontrol.store.RedisFlagSource;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

/**
 * Write path for runtime flag control, with <strong>optimistic concurrency</strong>. Persists a
 * {@link FlagDefinition} to Redis at {@code <key-prefix><name>} only if the caller's
 * {@link FlagDefinition#version()} still matches the stored one (compare-and-set in a Lua script,
 * atomic on the Redis server) — so two admins editing the same flag can't silently overwrite each
 * other; the loser gets a {@link FlagConflictException} (HTTP 409) and re-reads.
 *
 * <p>On success the stored copy carries {@code version+1}, the local cache is invalidated, and a
 * change signal is published so every instance drops its cache within milliseconds (see
 * {@link FlagChangeNotifier}). Only wired when Redis is available.
 */
@Singleton
@Requires(beans = RedisClient.class)
public class FlagAdminService {

    // CAS on the version embedded in the stored JSON (Redis ships cjson). Returns the new version,
    // or -1 on a version mismatch. ARGV: [expectedVersion, newJson-with-version=expected+1].
    private static final String CAS_LUA = """
            local cur = redis.call('GET', KEYS[1])
            local curVer = 0
            if cur then curVer = tonumber(cjson.decode(cur).version) or 0 end
            local expected = tonumber(ARGV[1])
            if expected ~= curVer then return -1 end
            redis.call('SET', KEYS[1], ARGV[2])
            return curVer + 1
            """;

    private final FeatureRedisCommandsProvider redis;
    private final ObjectMapper objectMapper;
    private final FeatureSettings settings;
    private final FlagChangeNotifier notifier;
    @Nullable
    private final RedisFlagSource dynamicSource;

    public FlagAdminService(FeatureRedisCommandsProvider redis,
                            ObjectMapper objectMapper,
                            FeatureSettings settings,
                            FlagChangeNotifier notifier,
                            @Nullable RedisFlagSource dynamicSource) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.notifier = notifier;
        this.dynamicSource = dynamicSource;
    }

    /**
     * Upserts a flag using compare-and-set on {@code definition.version()}. Send version 0 to create;
     * to update, send the version you last read. On success the returned definition carries the new
     * version.
     *
     * @throws FlagConflictException if another writer bumped the version first (HTTP 409).
     */
    public FlagDefinition put(FlagDefinition definition) {
        long expected = definition.version();
        FlagDefinition next = definition.withVersion(expected + 1);
        try {
            String json = objectMapper.writeValueAsString(next);
            Long result = redis.commands().eval(CAS_LUA, ScriptOutputType.INTEGER,
                    new String[]{key(definition.name())},
                    Long.toString(expected), json);
            if (result != null && result == -1L) {
                throw new FlagConflictException(definition.name(), expected, currentVersion(definition.name()));
            }
        } catch (FlagConflictException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist flag " + definition.name(), e);
        }
        invalidate(definition.name());
        notifier.publish(definition.name());
        return next;
    }

    /** Removes the dynamic override so the YAML baseline applies again. */
    public void delete(String name) {
        redis.commands().del(key(name));
        invalidate(name);
        notifier.publish(name);
    }

    private long currentVersion(String name) {
        try {
            String json = redis.commands().get(key(name));
            return json == null ? 0 : objectMapper.readValue(json, FlagDefinition.class).version();
        } catch (Exception e) {
            return 0;
        }
    }

    private void invalidate(String name) {
        if (dynamicSource != null) {
            dynamicSource.invalidate(name);
        }
    }

    private String key(String name) {
        return settings.getKeyPrefix() + name;
    }
}
