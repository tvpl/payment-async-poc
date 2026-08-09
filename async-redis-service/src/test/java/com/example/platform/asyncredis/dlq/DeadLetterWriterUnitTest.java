package com.example.platform.asyncredis.dlq;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-07: exactly {@code max-deliveries} attempts must occur before a message is dead-lettered - no
 * off-by-one. {@code exceedsMaxDeliveries} is the single predicate {@code JobWorker.reclaim} uses to
 * decide DLQ vs. one more {@code XCLAIM}, so its boundary is where the old bug lived: {@code
 * redeliveryCount > maxDeliveries} let one extra redelivery (and processing attempt) through.
 */
class DeadLetterWriterUnitTest {

    private static DeadLetterWriter writer(int maxDeliveries) {
        AsyncRedisProperties props = new AsyncRedisProperties();
        props.setMaxDeliveries(maxDeliveries);
        return new DeadLetterWriter(props);
    }

    @Test
    void belowTheThresholdOneMoreAttemptIsStillAllowed() {
        DeadLetterWriter writer = writer(5);

        assertFalse(writer.exceedsMaxDeliveries(4), "4 deliveries with a limit of 5 must reclaim again");
    }

    @Test
    void atExactlyMaxDeliveriesNoFurtherAttemptIsAllowed() {
        DeadLetterWriter writer = writer(5);

        assertTrue(writer.exceedsMaxDeliveries(5),
                "the 5th delivery having already happened means a 6th must never be attempted");
    }

    @Test
    void aboveTheThresholdIsAlsoRejected() {
        DeadLetterWriter writer = writer(5);

        assertTrue(writer.exceedsMaxDeliveries(6));
    }

    @Test
    void aLimitOfOneAllowsExactlyOneAttempt() {
        // The tightest boundary: the very first delivery (count 1) must already be the last one.
        DeadLetterWriter writer = writer(1);

        assertTrue(writer.exceedsMaxDeliveries(1),
                "max-deliveries=1 must dead-letter after the first delivery, not the second");
    }
}
