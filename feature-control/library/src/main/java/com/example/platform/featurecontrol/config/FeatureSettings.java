package com.example.platform.featurecontrol.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/**
 * Library-wide settings for feature control. Bound from {@code platform.features.*}.
 *
 * <ul>
 *   <li>{@code redis-enabled} — turn the dynamic Redis override on/off (off = YAML-only).</li>
 *   <li>{@code cache-ttl} — how long a Redis-read definition is cached in-process. This is the
 *       propagation window for a runtime flip: smaller = faster flips, more Redis reads.</li>
 *   <li>{@code key-prefix} — Redis key namespace for flags ({@code <prefix><flag>}).</li>
 * </ul>
 */
@ConfigurationProperties("platform.features")
public class FeatureSettings {

    private boolean redisEnabled = true;
    private Duration cacheTtl = Duration.ofSeconds(5);
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

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
