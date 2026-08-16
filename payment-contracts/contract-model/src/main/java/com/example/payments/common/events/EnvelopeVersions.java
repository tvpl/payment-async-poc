package com.example.payments.common.events;

/**
 * Validates {@link EventEnvelope#eventVersion()} against the majors this contract knows how to
 * read. Only the major component gates compatibility (API-02/API-01): minor bumps stay additive
 * and readable by every consumer on the same major, matching {@link EventEnvelope#CURRENT_VERSION}.
 */
public final class EnvelopeVersions {

    private static final int KNOWN_MAJOR = 1;

    private EnvelopeVersions() {
    }

    /**
     * Returns the parsed major version when {@code eventVersion} is on a known major.
     *
     * @throws UnknownEventMajorException when {@code eventVersion} is missing, malformed, or on
     *                                     a major this contract does not know how to read
     */
    public static int assertKnownMajor(String eventVersion) {
        int major = parseMajor(eventVersion);
        if (major != KNOWN_MAJOR) {
            throw new UnknownEventMajorException(eventVersion);
        }
        return major;
    }

    private static int parseMajor(String eventVersion) {
        if (eventVersion == null || eventVersion.isBlank()) {
            throw new UnknownEventMajorException(eventVersion);
        }
        String majorPart = eventVersion.split("\\.", 2)[0];
        try {
            return Integer.parseInt(majorPart);
        } catch (NumberFormatException notNumeric) {
            throw new UnknownEventMajorException(eventVersion);
        }
    }
}
