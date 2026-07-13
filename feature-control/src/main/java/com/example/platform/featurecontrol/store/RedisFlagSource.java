package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.spi.FlagSource;
import io.lettuce.core.RedisClient;
import io.micronaut.context.annotation.Requires;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic {@link FlagSource} backed by Redis, so a flag can be flipped at runtime across all 30+
 * apps without a redeploy. A flag lives at {@code <key-prefix><name>} as the JSON of a
 * {@link FlagDefinition}. Reads are cached in-process for {@code cache-ttl} to bound Redis traffic;
 * that TTL is the flip propagation window.
 *
 * <p><strong>Fail-safe, never fail-open:</strong> if Redis is down or a value is malformed, this
 * source returns {@link Optional#empty()} (or the last good cached value), so the composite falls
 * back to the YAML baseline rather than to an undefined/on state. Only active when a
 * {@link RedisClient} bean is present and {@code platform.features.redis-enabled} is not {@code false}.
 */
@Singleton
@Requires(beans = RedisClient.class)
@Requires(property = "platform.features.redis-enabled", notEquals = "false", defaultValue = "true")
public class RedisFlagSource implements FlagSource {

    private static final Logger LOG = LoggerFactory.getLogger(RedisFlagSource.class);

    private record Cached(FlagDefinition definition, long expiresAtMillis) {
    }

    private final FeatureRedisCommandsProvider redis;
    private final ObjectMapper objectMapper;
    private final FeatureSettings settings;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public RedisFlagSource(FeatureRedisCommandsProvider redis,
                           ObjectMapper objectMapper,
                           FeatureSettings settings) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    @Override
    public Optional<FlagDefinition> find(String name) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(name);
        if (cached != null && cached.expiresAtMillis() > now) {
            return Optional.ofNullable(cached.definition());
        }
        try {
            String json = redis.commands().get(settings.getKeyPrefix() + name);
            FlagDefinition definition = json == null
                    ? null
                    : objectMapper.readValue(json, FlagDefinition.class);
            cache.put(name, new Cached(definition, now + settings.getCacheTtl().toMillis()));
            return Optional.ofNullable(definition);
        } catch (Exception e) {
            // Fail-safe: serve the last good value if we have one, otherwise defer to the baseline.
            LOG.debug("Redis flag lookup failed for {} ({}); falling back", name, e.getMessage());
            return cached != null ? Optional.ofNullable(cached.definition()) : Optional.empty();
        }
    }

    /** Drops the in-process cache entry so the next read reflects a just-written value immediately. */
    public void invalidate(String name) {
        cache.remove(name);
    }
}
