package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.source.CacheJitter;
import com.example.platform.featurecontrol.source.FlagKeyReader;
import com.example.platform.featurecontrol.source.StalePolicy;
import com.example.platform.featurecontrol.spi.FlagSource;
import io.lettuce.core.RedisClient;
import io.micronaut.context.annotation.Requires;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic {@link FlagSource} backed by Redis, so a flag can be flipped at runtime across all 30+
 * apps without a redeploy. A flag lives at {@code <key-prefix><name>} as the JSON of a
 * {@link FlagDefinition}. Reads are cached in-process for {@code cache-ttl} (jittered per key, FTR-02)
 * to bound Redis traffic; that TTL is the flip propagation window.
 *
 * <p><strong>Fail-safe, never fail-open (FTR-02):</strong> on a refresh failure, a last-known-good
 * value is served only while younger than {@code max-stale}; once it's older than that (or was never
 * fetched), the configured {@link com.example.platform.featurecontrol.source.StaleFallback} applies —
 * defer to the composite's YAML baseline, or force the flag off. See {@link StalePolicy}. Age is
 * observable via {@link #ageOf(String)}.
 *
 * <p><strong>Single-flight (FTR-02):</strong> when many concurrent callers miss the cache for the
 * same key at once, only one of them actually talks to Redis; the rest wait for and reuse that
 * result, instead of stampeding Redis with duplicate lookups.
 *
 * <p>Only active when a {@link RedisClient} bean is present and {@code platform.features.redis-enabled}
 * is not {@code false}.
 */
@Singleton
@Requires(beans = RedisClient.class)
@Requires(property = "platform.features.redis-enabled", notEquals = "false", defaultValue = "true")
public class RedisFlagSource implements FlagSource {

    private static final Logger LOG = LoggerFactory.getLogger(RedisFlagSource.class);

    private record Cached(FlagDefinition definition, long fetchedAtMillis, long expiresAtMillis) {
    }

    private final FlagKeyReader redis;
    private final ObjectMapper objectMapper;
    private final FeatureSettings settings;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> refreshLocks = new ConcurrentHashMap<>();
    private final Random jitterRandom = new Random();

    public RedisFlagSource(FlagKeyReader redis,
                           ObjectMapper objectMapper,
                           FeatureSettings settings) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    @Override
    public Optional<FlagDefinition> find(String name) {
        long now = System.currentTimeMillis();
        Cached fresh = freshCacheEntry(name, now);
        if (fresh != null) {
            return Optional.ofNullable(fresh.definition());
        }

        // Single-flight: only the first thread to see an expired/missing entry talks to Redis; any
        // concurrent caller for the same key blocks here and then reuses that thread's result.
        Object lock = refreshLocks.computeIfAbsent(name, key -> new Object());
        synchronized (lock) {
            Cached recheck = freshCacheEntry(name, System.currentTimeMillis());
            if (recheck != null) {
                return Optional.ofNullable(recheck.definition());
            }
            return refresh(name);
        }
    }

    /** @return the cache entry for {@code name} if present and not yet expired, else {@code null}. */
    private Cached freshCacheEntry(String name, long now) {
        Cached cached = cache.get(name);
        return cached != null && cached.expiresAtMillis() > now ? cached : null;
    }

    private Optional<FlagDefinition> refresh(String name) {
        Cached previous = cache.get(name);
        try {
            String json = redis.get(settings.getKeyPrefix() + name);
            FlagDefinition definition = json == null
                    ? null
                    : objectMapper.readValue(json, FlagDefinition.class);
            long now = System.currentTimeMillis();
            long ttlMillis = CacheJitter.jittered(
                    settings.getCacheTtl().toMillis(), settings.getCacheTtlJitter(), jitterRandom);
            cache.put(name, new Cached(definition, now, now + ttlMillis));
            return Optional.ofNullable(definition);
        } catch (Exception e) {
            LOG.debug("Redis flag lookup failed for {} ({}); applying stale policy", name, e.getMessage());
            long ageMillis = previous == null
                    ? Long.MAX_VALUE
                    : Math.max(0, System.currentTimeMillis() - previous.fetchedAtMillis());
            return StalePolicy.apply(name, previous == null ? null : previous.definition(), ageMillis,
                    settings.getMaxStale().toMillis(), settings.getStaleFallback());
        }
    }

    /**
     * FTR-02: the observable age of the value currently cached for {@code name} — how long ago it was
     * last <em>successfully</em> fetched from Redis. Empty if nothing has ever been fetched.
     */
    public Optional<Duration> ageOf(String name) {
        Cached cached = cache.get(name);
        if (cached == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - cached.fetchedAtMillis())));
    }

    /** Drops the in-process cache entry so the next read reflects a just-written value immediately. */
    public void invalidate(String name) {
        cache.remove(name);
    }

    /** Drops the entire cache (used on a wildcard change signal). */
    public void invalidateAll() {
        cache.clear();
    }
}
