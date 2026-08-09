package com.example.payments.coremock;

import jakarta.inject.Singleton;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

@Singleton
public final class CoreSimulationDecisionEngine {

    public Decision decide(String requestId, CoreBehaviorProperties.Behavior behavior) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(behavior, "behavior");

        ByteBuffer hash = ByteBuffer.wrap(hash(behavior.seed() + ":" + requestId));
        int outcomeBucket = (int) Math.floorMod(hash.getLong(), 100L);
        long latencyRange = (long) behavior.latencyMaxMs() - behavior.latencyMinMs() + 1L;
        int latencyMs = behavior.latencyMinMs()
                + (int) Math.floorMod(hash.getLong(), latencyRange);

        if (outcomeBucket < behavior.failPct()) {
            return new Decision(Outcome.TRANSIENT_FAILURE, latencyMs, null);
        }
        if (outcomeBucket < behavior.failPct() + behavior.declinePct()) {
            return new Decision(Outcome.DECLINED, latencyMs, null);
        }

        String authorizationCode = String.format("%06d", Math.floorMod(hash.getLong(), 1_000_000L));
        return new Decision(Outcome.APPROVED, latencyMs, authorizationCode);
    }

    private static byte[] hash(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    public enum Outcome {
        APPROVED,
        DECLINED,
        TRANSIENT_FAILURE
    }

    public record Decision(Outcome outcome, int latencyMs, String authorizationCode) {
    }
}
