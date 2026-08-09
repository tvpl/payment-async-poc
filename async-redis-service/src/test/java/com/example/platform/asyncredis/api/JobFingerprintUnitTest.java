package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.dto.SubmitJobRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * RED-08: idempotency has to decide whether two submissions are "the same job", so the fingerprint
 * must be stable for identical payloads and different for anything a caller would consider a
 * different request.
 */
class JobFingerprintUnitTest {

    @Test
    void identicalPayloadsFingerprintIdentically() {
        SubmitJobRequest first = new SubmitJobRequest("ORDER-1", 10_000L, "hello");
        SubmitJobRequest second = new SubmitJobRequest("ORDER-1", 10_000L, "hello");

        assertEquals(JobFingerprint.of(first), JobFingerprint.of(second));
    }

    @Test
    void aDifferentAmountIsADifferentJob() {
        String base = JobFingerprint.of(new SubmitJobRequest("ORDER-1", 10_000L, "hello"));
        String changed = JobFingerprint.of(new SubmitJobRequest("ORDER-1", 10_001L, "hello"));

        assertNotEquals(base, changed);
    }

    @Test
    void aDifferentReferenceIsADifferentJob() {
        String base = JobFingerprint.of(new SubmitJobRequest("ORDER-1", 10_000L, "hello"));
        String changed = JobFingerprint.of(new SubmitJobRequest("ORDER-2", 10_000L, "hello"));

        assertNotEquals(base, changed);
    }

    @Test
    void anAbsentNoteIsNotAnEmptyNote() {
        String absent = JobFingerprint.of(new SubmitJobRequest("ORDER-1", 10_000L, null));
        String empty = JobFingerprint.of(new SubmitJobRequest("ORDER-1", 10_000L, ""));

        assertNotEquals(absent, empty);
    }

    @Test
    void adjacentFieldsCannotBeShiftedIntoTheSameFingerprint() {
        // Concatenated without a delimiter both of these are "ORDER-1" + "100", so an undelimited
        // fingerprint would treat a 10-cent job and a 100-cent job as the same submission.
        String left = JobFingerprint.of(new SubmitJobRequest("ORDER-1", 10L, "0"));
        String right = JobFingerprint.of(new SubmitJobRequest("ORDER-1", 100L, ""));

        assertNotEquals(left, right);
    }
}
