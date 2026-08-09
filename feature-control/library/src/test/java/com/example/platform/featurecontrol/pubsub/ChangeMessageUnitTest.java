package com.example.platform.featurecontrol.pubsub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChangeMessageUnitTest {

    @Test
    void parsesFlagNameAndTimestamp() {
        ChangeMessage message = ChangeMessage.parse("demo-toggle|1700000000000");
        assertEquals("demo-toggle", message.flagName());
        assertEquals(1700000000000L, message.publishedAtMillis());
    }

    @Test
    void parsesTheWildcard() {
        ChangeMessage message = ChangeMessage.parse("*|1700000000000");
        assertEquals("*", message.flagName());
        assertEquals(1700000000000L, message.publishedAtMillis());
    }

    @Test
    void bareNameWithNoSeparatorHasNoTimestamp() {
        ChangeMessage message = ChangeMessage.parse("legacy-flag-name");
        assertEquals("legacy-flag-name", message.flagName());
        assertNull(message.publishedAtMillis());
    }

    @Test
    void nonNumericSuffixFallsBackToTheWholePayloadAsTheName() {
        ChangeMessage message = ChangeMessage.parse("weird|not-a-number");
        assertEquals("weird|not-a-number", message.flagName());
        assertNull(message.publishedAtMillis());
    }

    @Test
    void trailingSeparatorWithNoSuffixFallsBackToTheWholePayload() {
        ChangeMessage message = ChangeMessage.parse("demo-toggle|");
        assertEquals("demo-toggle|", message.flagName());
        assertNull(message.publishedAtMillis());
    }
}
