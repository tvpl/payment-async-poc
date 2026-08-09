package com.example.platform.featurecontrol.source;

/**
 * The minimal read surface {@code RedisFlagSource} needs from Redis: a single key lookup. Narrowed
 * on purpose (instead of depending on the full {@code FeatureRedisCommandsProvider}/Lettuce command
 * surface) so the stale/single-flight/jitter behavior in {@code RedisFlagSource} is testable against
 * a real Redis connection with deterministic, controllable fault injection (FTR-02) — a test double
 * only has to implement one method, not the entire Redis command interface.
 */
public interface FlagKeyReader {

    /** @return the raw value at {@code key}, or {@code null} if absent. Throws on any I/O failure. */
    String get(String key);
}
