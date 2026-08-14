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
 *   <li>{@code failure-backoff} — how long, per key, a failed Redis read is remembered so subsequent
 *       reads serve the stale policy directly without acquiring the single-flight lock or touching
 *       Redis again; jittered by {@code cache-ttl-jitter}. Bounds Redis traffic during a sustained
 *       outage to roughly one attempt per key per window instead of every waiting thread paying a
 *       full command timeout in series (AUD-14).</li>
 *   <li>{@code stale-fallback} — what {@link com.example.platform.featurecontrol.store.RedisFlagSource}
 *       does once {@code max-stale} is exceeded (or nothing was ever fetched): defer to the YAML
 *       baseline, or force the flag off (FTR-02).</li>
 *   <li>{@code key-prefix} — Redis key namespace for flags ({@code <prefix><flag>}).</li>
 *   <li>{@code convergence-alert-threshold} — the approved limit for how long a change may take to
 *       reach an instance after publish before {@code ConvergenceTracker} logs a degradation alert
 *       (FTR-03).</li>
 *   <li>{@code pubsub-reconnect-base-delay}/{@code pubsub-reconnect-max-delay} — bounds for the
 *       jittered backoff {@code FlagChangeSubscriber} uses between reconnect attempts (FTR-03).</li>
 *   <li>{@code metric-cardinality-limit} — the most distinct flag or variant names decision metrics
 *       and exposure logs will ever track per dimension before collapsing further values to
 *       {@code "other"} (FTR-05).</li>
 * </ul>
 */
@ConfigurationProperties("platform.features")
public class FeatureSettings {

    private boolean redisEnabled = true;
    private Duration cacheTtl = Duration.ofSeconds(5);
    private double cacheTtlJitter = 0.1;
    private Duration maxStale = Duration.ofMinutes(5);
    private StaleFallback staleFallback = StaleFallback.BASELINE;
    private Duration failureBackoff = Duration.ofSeconds(1);
    private String keyPrefix = "feature:";
    private boolean masterEnabled = true;
    private Duration convergenceAlertThreshold = Duration.ofSeconds(2);
    private Duration pubsubReconnectBaseDelay = Duration.ofMillis(200);
    private Duration pubsubReconnectMaxDelay = Duration.ofSeconds(30);
    private int metricCardinalityLimit = 200;

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

    public Duration getFailureBackoff() {
        return failureBackoff;
    }

    public void setFailureBackoff(Duration failureBackoff) {
        this.failureBackoff = failureBackoff;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getConvergenceAlertThreshold() {
        return convergenceAlertThreshold;
    }

    public void setConvergenceAlertThreshold(Duration convergenceAlertThreshold) {
        this.convergenceAlertThreshold = convergenceAlertThreshold;
    }

    public Duration getPubsubReconnectBaseDelay() {
        return pubsubReconnectBaseDelay;
    }

    public void setPubsubReconnectBaseDelay(Duration pubsubReconnectBaseDelay) {
        this.pubsubReconnectBaseDelay = pubsubReconnectBaseDelay;
    }

    public Duration getPubsubReconnectMaxDelay() {
        return pubsubReconnectMaxDelay;
    }

    public void setPubsubReconnectMaxDelay(Duration pubsubReconnectMaxDelay) {
        this.pubsubReconnectMaxDelay = pubsubReconnectMaxDelay;
    }

    public int getMetricCardinalityLimit() {
        return metricCardinalityLimit;
    }

    public void setMetricCardinalityLimit(int metricCardinalityLimit) {
        this.metricCardinalityLimit = metricCardinalityLimit;
    }
}
