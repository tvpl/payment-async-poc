package com.example.payments.common.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvelopeVersionsUnitTest {

    @Test
    void acceptsTheCurrentKnownMajor() {
        assertEquals(1, EnvelopeVersions.assertKnownMajor(EventEnvelope.CURRENT_VERSION));
    }

    @Test
    void acceptsAnyMinorOnTheKnownMajor() {
        assertEquals(1, EnvelopeVersions.assertKnownMajor("1.7"));
    }

    @Test
    void rejectsAnUnknownMajor() {
        UnknownEventMajorException exception = assertThrows(UnknownEventMajorException.class,
                () -> EnvelopeVersions.assertKnownMajor("2.0"));

        assertEquals("Unknown event major version: 2.0", exception.getMessage());
    }

    @Test
    void rejectsAMalformedVersion() {
        assertThrows(UnknownEventMajorException.class, () -> EnvelopeVersions.assertKnownMajor("not-a-version"));
    }

    @Test
    void rejectsAMissingVersion() {
        assertThrows(UnknownEventMajorException.class, () -> EnvelopeVersions.assertKnownMajor(null));
    }
}
