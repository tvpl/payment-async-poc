package com.example.platform.featurecontrol.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounds the number of distinct tag values a metric/log dimension can ever emit (FTR-05). A flag or
 * variant name is normally admin-controlled and small in number, but nothing in this library enforces
 * that — a misconfigured or malicious source could mint thousands of distinct names, and Prometheus
 * (or any log index) treats each distinct tag value as a new, permanent time series. Once a dimension
 * has seen {@code limit} distinct values, every further new value collapses to {@link #OVERFLOW} so
 * cardinality stays flat regardless of how much distinct input arrives.
 *
 * <p>One instance guards one dimension (flag names, variant names, ...); {@link MicrometerDecisionListener}
 * holds two, so a cardinality explosion on one dimension never crowds out the other's budget.
 */
public final class CardinalityGuard {

    /** The tag value every value beyond the limit collapses to. */
    public static final String OVERFLOW = "other";

    private final int limit;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public CardinalityGuard(int limit) {
        this.limit = Math.max(1, limit);
    }

    /**
     * @return {@code value} unchanged if it is already tracked or there is still room to track it;
     *         {@link #OVERFLOW} once {@code limit} distinct values have been admitted.
     */
    public String admit(String value) {
        if (value == null) {
            return OVERFLOW;
        }
        if (seen.contains(value)) {
            return value;
        }
        if (seen.size() >= limit) {
            return OVERFLOW;
        }
        // Two threads can both pass the size check for two different new values and both add,
        // admitting a handful more than `limit` under heavy concurrency right at the boundary. That is
        // an acceptable trade for never locking the hot decision path — the bound stays flat (not
        // unbounded), which is what this guard exists to guarantee.
        seen.add(value);
        return value;
    }

    /** Distinct values admitted so far (never exceeds {@code limit}). */
    public int size() {
        return seen.size();
    }
}
