package com.example.platform.featurecontrol.bucketing;

import com.example.platform.featurecontrol.model.Variant;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Deterministic bucketing for percentage roll-outs and weighted variants. The bucket is a pure
 * function of {@code (salt, key)} — same input always yields the same bucket — so a user stays on
 * the same side of an A/B split across requests and across services ("sticky"). The {@code salt} is
 * the flag name, which decorrelates independent flags (a user isn't unlucky on every flag at once).
 *
 * <p>Uses 64-bit FNV-1a — a fast, well-distributed, dependency-free hash. It is not cryptographic;
 * it does not need to be. What matters is a uniform, stable spread over {@code [0,100)}.
 */
public final class Bucketer {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Bucketer() {
    }

    /** @return a stable bucket in {@code [0,100)} for the given salt/key pair. */
    public static int bucket(String salt, String key) {
        long h = hash(salt + ":" + key);
        // floorMod keeps the result non-negative even for negative hashes.
        return (int) Math.floorMod(h, 100L);
    }

    /**
     * Selects a weighted variant deterministically for the given key. Weights are normalized to the
     * total; a variant with weight 0 is never selected. Returns {@code null} for an empty list.
     */
    public static Variant select(List<Variant> variants, String salt, String key) {
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        long total = 0;
        for (Variant v : variants) {
            total += v.weight();
        }
        if (total <= 0) {
            return variants.get(0);
        }
        // Map the stable hash into [0,total) and walk the cumulative weights.
        long point = Math.floorMod(hash(salt + ":" + key), total);
        long cumulative = 0;
        for (Variant v : variants) {
            cumulative += v.weight();
            if (point < cumulative) {
                return v;
            }
        }
        return variants.get(variants.size() - 1);
    }

    private static long hash(String value) {
        long h = FNV_OFFSET_BASIS;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= FNV_PRIME;
        }
        return h;
    }
}
