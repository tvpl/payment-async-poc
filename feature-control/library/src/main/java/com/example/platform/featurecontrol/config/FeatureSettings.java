package com.example.platform.featurecontrol.config;

import com.example.platform.featurecontrol.source.StaleFallback;
import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/**
 * Library-wide settings for feature control. Bound from {@code platform.features.*}.
 *
 * <ul>
 *   <li>{@code redis-enabled} — turn the dynamic Redis override on/off (off = YAML-only).</li>
 *   <li>{@code cache-ttl} — how long a Redis-read definition is cached in-process. This is the
 *       propagation window for a runtime flip: smaller = faster flips, more Redis reads.</li>
 *   <li>{@code cache-ttl-jitter} — fractional jitter (0..1) applied to {@code cache-ttl} per key so
 *       many flags don't all expire in lockstep and thunder-herd Redis at once (FTR-02).</li>
 *   <li>{@code max-stale} — the longest a last-known-good value may keep being served once Redis
 *       stops answering; beyond this, {@code stale-fallback} applies (FTR-02).</li>
 *   <li>{@code stale-fallback} — what {@link com.example.platform.featurecontrol.store.RedisFlagSource}
 *       does once {@code max-stale} is exceeded (or nothing was ever fetched): defer to the YAML
 *       baseline, or force the flag off (FTR-02).</li>
 *   <li>{@code key-prefix} — Redis key namespace for flags ({@code <prefix><flag>}).</li>
 * </ul>
 */
@ConfigurationProperties("platform.features")
public class FeatureSettings {

    private boolean redisEnabled = true;
    private Duration cacheTtl = Duration.ofSeconds(5);
    private double cacheTtlJitter = 0.1;
    private Duration maxStale = Duration.ofMinutes(5);
    private StaleFallback staleFallback = StaleFallback.BASELINE;
    private String keyPrefix = "feature:";
    private boolean masterEnabled = true;

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    /** Global master switch. When false, every flag resolves off/default (static kill-switch). */
    public boolean isMasterEnabled() {
        return masterEnabled;
    }

    public void setMasterEnabled(boolean masterEnabled) {
        this.masterEnabled = masterEnabled;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public double getCacheTtlJitter() {
        return cacheTtlJitter;
    }

    public void setCacheTtlJitter(double cacheTtlJitter) {
        this.cacheTtlJitter = cacheTtlJitter;
    }

    public Duration getMaxStale() {
        return maxStale;
    }

    public void setMaxStale(Duration maxStale) {
        this.maxStale = maxStale;
    }

    public StaleFallback getStaleFallback() {
        return staleFallback;
    }

    public void setStaleFallback(StaleFallback staleFallback) {
        this.staleFallback = staleFallback;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
