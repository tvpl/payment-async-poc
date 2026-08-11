package com.example.platform.featurecontrol.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FTR-05: "impedir user/bucketing key ... arbitrários em logs" — the hashed form must never leak the raw id. */
class SubjectHasherUnitTest {

    @Test
    void theRawSubjectNeverAppearsInTheHashedToken() {
        String raw = "alice@example.com";

        String token = SubjectHasher.hash(raw);

        assertFalse(token.contains(raw), "the hashed token must not contain the raw PII value");
        assertNotEquals(raw, token);
    }

    @Test
    void theSameSubjectAlwaysHashesToTheSameToken() {
        assertEquals(SubjectHasher.hash("bob"), SubjectHasher.hash("bob"));
    }

    @Test
    void differentSubjectsHashToDifferentTokens() {
        assertNotEquals(SubjectHasher.hash("alice"), SubjectHasher.hash("bob"));
    }

    @Test
    void aNullOrBlankSubjectHashesToAFixedPlaceholderNeverNull() {
        assertEquals("none", SubjectHasher.hash(null));
        assertEquals("none", SubjectHasher.hash("   "));
    }

    @Test
    void theTokenIsAShortFixedLengthHexString() {
        String token = SubjectHasher.hash("a-fairly-long-user-identifier-value");

        assertEquals(12, token.length());
        assertTrue(token.matches("[0-9a-f]+"), "token must be lowercase hex: was " + token);
    }
}
