package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.dto.SubmitJobRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic fingerprint of the fields that make two submissions "the same job" (RED-08).
 *
 * <p>Fields are pipe-delimited before hashing so adjacent values cannot be confused for one another
 * at a boundary: {@code ("AB", "C")} and {@code ("A", "BC")} must not collide. A null note hashes
 * differently from an empty one, because "no note" and "an empty note" are different submissions.
 */
public final class JobFingerprint {

    private static final String DELIMITER = "|";
    /** NUL cannot appear in a JSON string body, so it can only ever mean "field absent". */
    private static final String NULL_MARKER = "\0";

    private JobFingerprint() {
    }

    public static String of(SubmitJobRequest request) {
        String canonical = String.join(DELIMITER,
                marker(request.reference()),
                Long.toString(request.amountCents()),
                marker(request.note()));
        return sha256Hex(canonical);
    }

    private static String marker(String value) {
        return value == null ? NULL_MARKER : value;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to fingerprint idempotent jobs", e);
        }
    }
}
