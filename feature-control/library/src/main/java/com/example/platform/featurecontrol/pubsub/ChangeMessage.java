package com.example.platform.featurecontrol.pubsub;

/**
 * The parsed shape of a flag-changed pub/sub payload: {@code <flagName-or-*>|<publishedAtEpochMillis>}.
 * A payload that doesn't match the envelope (no {@code |}, or a non-numeric suffix — e.g. a message
 * from an older library version) is treated as a bare flag name with an unknown publish time, so
 * invalidation still happens; only convergence measurement is skipped for it.
 */
public record ChangeMessage(String flagName, Long publishedAtMillis) {

    public static ChangeMessage parse(String payload) {
        int separator = payload.lastIndexOf('|');
        if (separator <= 0 || separator == payload.length() - 1) {
            return new ChangeMessage(payload, null);
        }
        String namePart = payload.substring(0, separator);
        String timestampPart = payload.substring(separator + 1);
        try {
            return new ChangeMessage(namePart, Long.parseLong(timestampPart));
        } catch (NumberFormatException e) {
            return new ChangeMessage(payload, null);
        }
    }
}
