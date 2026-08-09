package com.example.payments.coremock;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreSimulationDecisionEngineTest {

    private final CoreSimulationDecisionEngine engine = new CoreSimulationDecisionEngine();

    @Test
    void returnsTheSameDecisionForTheSameRequestAndSeed() {
        var behavior = behavior(10, 300, 15, 5, 20260808L);

        var first = engine.decide("request-123", behavior);
        var redelivery = engine.decide("request-123", behavior);

        assertEquals(first, redelivery);
    }

    @Test
    void approvesWhenFailureAndDeclinePercentagesAreZero() {
        var decision = engine.decide("approved-request", behavior(0, 0, 0, 0, 11L));

        assertEquals(CoreSimulationDecisionEngine.Outcome.APPROVED, decision.outcome());
    }

    @Test
    void failsWhenFailurePercentageCoversTheWholeRange() {
        var decision = engine.decide("failed-request", behavior(0, 0, 0, 100, 11L));

        assertEquals(CoreSimulationDecisionEngine.Outcome.TRANSIENT_FAILURE, decision.outcome());
        assertNull(decision.authorizationCode());
    }

    @Test
    void declinesWhenDeclinePercentageCoversTheWholeRange() {
        var decision = engine.decide("declined-request", behavior(0, 0, 100, 0, 11L));

        assertEquals(CoreSimulationDecisionEngine.Outcome.DECLINED, decision.outcome());
        assertNull(decision.authorizationCode());
    }

    @Test
    void keepsDeterministicLatencyInsideInclusiveBounds() {
        var behavior = behavior(50, 300, 0, 0, 99L);

        IntStream.range(0, 100).forEach(index -> {
            int latency = engine.decide("request-" + index, behavior).latencyMs();
            assertTrue(latency >= 50 && latency <= 300);
        });
    }

    @Test
    void usesTheConfiguredLatencyWhenBoundsAreEqual() {
        var decision = engine.decide("fixed-latency", behavior(125, 125, 0, 0, 99L));

        assertEquals(125, decision.latencyMs());
    }

    @Test
    void producesAStableSixDigitAuthorizationCodeForApproval() {
        var behavior = behavior(0, 0, 0, 0, 20260808L);

        var first = engine.decide("authorization-request", behavior);
        var redelivery = engine.decide("authorization-request", behavior);

        assertTrue(first.authorizationCode().matches("[0-9]{6}"));
        assertEquals(first.authorizationCode(), redelivery.authorizationCode());
    }

    private static CoreBehaviorProperties.Behavior behavior(
            int latencyMinMs,
            int latencyMaxMs,
            int declinePct,
            int failPct,
            long seed) {
        return new CoreBehaviorProperties.Behavior(
                latencyMinMs, latencyMaxMs, declinePct, failPct, seed);
    }
}
