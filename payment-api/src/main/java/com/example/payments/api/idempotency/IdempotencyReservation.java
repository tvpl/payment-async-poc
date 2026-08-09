package com.example.payments.api.idempotency;

import io.micronaut.serde.annotation.Serdeable;

/** What is stored, as one atomic value, under the {@code idem:<key>} Redis entry. */
@Serdeable
public record IdempotencyReservation(String requestId, String fingerprint) {
}
