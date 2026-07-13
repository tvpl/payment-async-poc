package com.example.platform.asyncredis.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * API response: {@code COMPLETED} with a {@link JobResult} when the worker released the answer in
 * time, or {@code PROCESSING} (HTTP 202) with a {@code statusUrl} to poll otherwise.
 */
@Serdeable
public record JobResponse(
        String jobId,
        String status,
        String statusUrl,
        @Nullable JobResult result) {
}
