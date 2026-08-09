package com.example.platform.featurecontrol.pubsub;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConvergenceTrackerUnitTest {

    @Test
    void latencyWithinTheApprovedLimitIsNotDegraded() {
        ConvergenceTracker tracker = new ConvergenceTracker(Duration.ofSeconds(2));
        tracker.record("f", Duration.ofMillis(500));

        assertEquals(Duration.ofMillis(500), tracker.lastLatency().orElseThrow());
        assertFalse(tracker.isLastDegraded());
    }

    @Test
    void latencyAtExactlyTheApprovedLimitIsNotDegraded() {
        ConvergenceTracker tracker = new ConvergenceTracker(Duration.ofSeconds(2));
        tracker.record("f", Duration.ofSeconds(2));

        assertFalse(tracker.isLastDegraded(), "== limit is still within budget, not yet degraded");
    }

    @Test
    void latencyBeyondTheApprovedLimitIsDegraded() {
        ConvergenceTracker tracker = new ConvergenceTracker(Duration.ofSeconds(2));
        tracker.record("f", Duration.ofSeconds(2).plusMillis(1));

        assertTrue(tracker.isLastDegraded());
    }

    @Test
    void noRecordYetHasNoLastLatency() {
        ConvergenceTracker tracker = new ConvergenceTracker(Duration.ofSeconds(2));
        assertTrue(tracker.lastLatency().isEmpty());
        assertFalse(tracker.isLastDegraded());
    }

    @Test
    void aLaterGoodRecordClearsAnEarlierDegradedState() {
        ConvergenceTracker tracker = new ConvergenceTracker(Duration.ofSeconds(2));
        tracker.record("f", Duration.ofSeconds(10));
        assertTrue(tracker.isLastDegraded());

        tracker.record("f", Duration.ofMillis(100));
        assertFalse(tracker.isLastDegraded(), "degraded state must reflect only the most recent observation");
    }
}
