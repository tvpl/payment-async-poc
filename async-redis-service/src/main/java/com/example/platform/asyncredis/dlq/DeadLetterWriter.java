package com.example.platform.asyncredis.dlq;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes poison and malformed entries to the dead-letter stream (RED-07). Both callers in {@code
 * JobWorker} write here <strong>before</strong> ACKing the original message: a job the worker gives
 * up on is never silently dropped, its reason travels with it, and the DLQ stream itself is the
 * recovery path — durable, and readable by an operator or a future consumer, same as the main stream.
 */
@Singleton
public class DeadLetterWriter {

    /** Field carrying why an entry was dead-lettered, alongside its original body. */
    public static final String FIELD_REASON = "dlqReason";

    /** Reason used when a message exceeds {@code max-deliveries} without ever completing. */
    public static final String REASON_MAX_DELIVERIES_EXCEEDED = "max-deliveries-exceeded";

    private final AsyncRedisProperties props;

    public DeadLetterWriter(AsyncRedisProperties props) {
        this.props = props;
    }

    /**
     * True once {@code redeliveryCount} deliveries have already happened for a pending entry, meaning
     * a further redelivery must not occur.
     *
     * <p>Off-by-one note: Redis's own {@code delivery_count} starts at 1 on the entry's first {@code
     * XREADGROUP} and increments by exactly one on each {@code XCLAIM}, and every delivery is one
     * processing attempt ({@code JobWorker.handle}). {@code >= max-deliveries} (not {@code >}) is
     * what caps total attempts at exactly {@code max-deliveries}: at {@code redeliveryCount ==
     * max-deliveries - 1} the entry is still reclaimed for one more attempt, taking the count to
     * {@code max-deliveries} — its last permitted try. The previous {@code >} let one extra
     * redelivery through, for {@code max-deliveries + 1} total attempts.
     */
    public boolean exceedsMaxDeliveries(long redeliveryCount) {
        return redeliveryCount >= props.getMaxDeliveries();
    }

    /**
     * Writes {@code body} plus {@code reason} to the DLQ stream. Throws on any Redis-level failure —
     * the caller's signal not to ACK the original message, so a PEL redelivery retries this write.
     */
    public void write(RedisCommands<String, String> c, Map<String, String> body, String reason) {
        Map<String, String> dlqBody = new HashMap<>(body);
        dlqBody.put(FIELD_REASON, reason);
        c.xadd(props.getDlqStream(), dlqBody);
    }
}
