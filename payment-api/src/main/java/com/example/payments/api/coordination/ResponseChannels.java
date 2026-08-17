package com.example.payments.api.coordination;

import java.util.ArrayList;
import java.util.List;

/**
 * SCAL-05: derives the sharded Redis pub/sub channel names used to wake a waiting request from
 * its {@code requestId}. Both the publisher ({@code RedisStatusStore}) and the subscriber
 * ({@link ResponseCoordinator}) call into this single function so the hash-to-shard mapping can
 * never drift between the two sides.
 */
public final class ResponseChannels {

    private ResponseChannels() {
    }

    /** Deterministic shard index in {@code [0, shards)} for a given requestId. */
    public static int shardFor(String requestId, int shards) {
        int n = Math.max(1, shards);
        return Math.floorMod(requestId.hashCode(), n);
    }

    /** The sharded channel name {@code requestId} is published/subscribed on. */
    public static String shardChannel(String baseChannel, String requestId, int shards) {
        return baseChannel + "-" + shardFor(requestId, shards);
    }

    /** Every shard channel name for the configured shard count, in shard order (0..shards-1). */
    public static List<String> allShardChannels(String baseChannel, int shards) {
        int n = Math.max(1, shards);
        List<String> channels = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            channels.add(baseChannel + "-" + i);
        }
        return channels;
    }
}
