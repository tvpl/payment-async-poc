package com.example.platform.featurecontrol.metrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns a bucketing key (a real user/anon id — PII, per FTR-05) into a short, irreversible,
 * deterministic token safe to put in a log line: same subject always hashes to the same token (so an
 * operator can still correlate a rollout across log lines for one subject), but the token alone
 * cannot be reversed back to the id.
 */
public final class SubjectHasher {

    private static final int TOKEN_HEX_CHARS = 12;

    private SubjectHasher() {
    }

    public static String hash(String subject) {
        if (subject == null || subject.isBlank()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out, 0, TOKEN_HEX_CHARS / 2);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandated algorithm; this is unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
