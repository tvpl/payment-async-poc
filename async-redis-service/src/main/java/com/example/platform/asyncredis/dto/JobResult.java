package com.example.platform.asyncredis.dto;

import io.micronaut.serde.annotation.Serdeable;

/** The processed outcome the worker releases and the API returns. */
@Serdeable
public record JobResult(
        String jobId,
        String reference,
        long amountCents,
        long feeCents,
        String status,
        String processedBy,
        long processedAtEpochMs) {
}
