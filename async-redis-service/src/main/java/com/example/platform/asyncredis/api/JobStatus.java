package com.example.platform.asyncredis.api;

import io.micronaut.serde.annotation.Serdeable;

/**
 * What is stored under {@code job:<jobId>:status}. Written <strong>before</strong> the job reaches
 * the stream, so an accepted job is queryable from the instant it is accepted (RED-01).
 */
@Serdeable
public record JobStatus(String jobId, JobState state, long acceptedAtEpochMs) {
}
