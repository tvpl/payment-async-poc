package com.example.platform.asyncredis.retention;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-03: the ACKED-trim version gate rests entirely on {@code compareVersions}. Its boundary cases
 * matter as much as the happy path - an off-by-one here would either wrongly enable a trim strategy
 * the connected Redis does not have, or wrongly withhold it from a server that does.
 */
class StreamRetentionMonitorUnitTest {

    @Test
    void anOlderVersionComparesBelowTheMinimum() {
        assertTrue(StreamRetentionMonitor.compareVersions("7.0.15", "8.2.0") < 0);
    }

    @Test
    void theMinimumVersionItselfCompliesExactly() {
        assertEquals(0, StreamRetentionMonitor.compareVersions("8.2.0", "8.2.0"));
    }

    @Test
    void aNewerPatchVersionComparesAboveTheMinimum() {
        assertTrue(StreamRetentionMonitor.compareVersions("8.2.1", "8.2.0") > 0);
    }

    @Test
    void aNewerMajorVersionComparesAboveTheMinimumEvenWithASmallerPatch() {
        assertTrue(StreamRetentionMonitor.compareVersions("9.0.0", "8.2.0") > 0);
    }

    @Test
    void aMissingSegmentIsTreatedAsZero() {
        // "8.2" (no patch) must not be mistaken for older than "8.2.0" - a missing segment is 0, equal.
        assertEquals(0, StreamRetentionMonitor.compareVersions("8.2", "8.2.0"));
    }

    @Test
    void aNonNumericSuffixDoesNotThrow() {
        // A pre-release/build suffix must degrade gracefully, not throw NumberFormatException.
        assertTrue(StreamRetentionMonitor.compareVersions("8.2.0-rc1", "8.1.0") > 0);
    }
}
