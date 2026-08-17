package com.example.payments.api.coordination;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCAL-05: the shard-derivation used to publish/subscribe on the correlation channel must be a
 * single deterministic function, always in {@code [0, shards)}, and identical whichever way it
 * is called (directly by index, or through the channel-name helper).
 */
class ResponseChannelsUnitTest {

    @Test
    void shardForIsAlwaysWithinBounds() {
        for (int i = 0; i < 500; i++) {
            String requestId = "req-" + i;
            int shard = ResponseChannels.shardFor(requestId, 4);
            assertTrue(shard >= 0 && shard < 4, "shard out of bounds: " + shard);
        }
    }

    @Test
    void shardForIsDeterministicForTheSameRequestId() {
        String requestId = "req-fixed-abc123";

        int first = ResponseChannels.shardFor(requestId, 4);
        int second = ResponseChannels.shardFor(requestId, 4);

        assertEquals(first, second);
    }

    @Test
    void shardChannelAppendsTheShardIndexToTheBaseChannel() {
        String requestId = "req-fixed-abc123";
        int shard = ResponseChannels.shardFor(requestId, 4);

        String channel = ResponseChannels.shardChannel("payment-sim-responses", requestId, 4);

        assertEquals("payment-sim-responses-" + shard, channel);
    }

    @Test
    void allShardChannelsListsEveryShardInOrder() {
        List<String> channels = ResponseChannels.allShardChannels("payment-sim-responses", 3);

        assertEquals(
                List.of("payment-sim-responses-0", "payment-sim-responses-1", "payment-sim-responses-2"),
                channels);
    }

    @Test
    void aSingleShardAlwaysMapsToShardZero() {
        assertEquals(0, ResponseChannels.shardFor("any-request-id", 1));
        assertEquals(List.of("payment-sim-responses-0"),
                ResponseChannels.allShardChannels("payment-sim-responses", 1));
    }

    /**
     * With N=2, two distinct requestIds are found landing on distinct shards - the same
     * guarantee the sharded pub/sub IT (ResponseCoordinatorShardingIT) relies on for both
     * waiters to actually be exercised on different channels.
     */
    @Test
    void withTwoShardsBothShardsAreReachable() {
        boolean sawShardZero = false;
        boolean sawShardOne = false;
        for (int i = 0; i < 100 && !(sawShardZero && sawShardOne); i++) {
            int shard = ResponseChannels.shardFor("req-" + i, 2);
            sawShardZero |= shard == 0;
            sawShardOne |= shard == 1;
        }
        assertTrue(sawShardZero && sawShardOne, "expected requestIds landing on both shards 0 and 1");
    }
}
