package com.example.payments.api.error;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** RFC 7807 problem detail body (served as application/problem+json). */
@Serdeable
public record Problem(
        String type,
        String title,
        int status,
        @Nullable String detail
) {
    public static final String MEDIA_TYPE = "application/problem+json";

    public static Problem of(int status, String title, String detail) {
        return new Problem("about:blank", title, status, detail);
    }
}
