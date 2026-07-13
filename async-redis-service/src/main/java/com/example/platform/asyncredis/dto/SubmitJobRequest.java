package com.example.platform.asyncredis.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/** A unit of work to process asynchronously. Kept domain-neutral so the example stays self-contained. */
@Serdeable
public record SubmitJobRequest(
        @NotBlank String reference,
        @PositiveOrZero long amountCents,
        @Nullable String note) {
}
