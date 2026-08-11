package com.example.platform.featurecontrol.source;

/**
 * What {@code RedisFlagSource} does once a last-known-good value exceeds {@code max-stale} (or
 * nothing was ever fetched) and Redis is still not answering (FTR-02).
 */
public enum StaleFallback {

    /** Defer to the YAML baseline: {@code RedisFlagSource} reports "no override", same as a miss. */
    BASELINE,

    /** Force the flag off, regardless of what the YAML baseline says. */
    FAIL_CLOSED
}
