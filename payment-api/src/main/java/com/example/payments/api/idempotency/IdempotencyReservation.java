package com.example.payments.api.idempotency;

import io.micronaut.serde.annotation.Serdeable;

/**
 * What is stored, as one atomic value, under the {@code idem:<key>} Redis entry.
 *
 * <p>{@code publishState} and {@code publishLeaseExpiresAt} make the reservation
 * recoverable: they say whether the owning request reached Kafka and, while it has not,
 * until when its attempt is still considered in flight (PAY-03).
 */
@Serdeable
public record IdempotencyReservation(
        String requestId,
        String fingerprint,
        PublishState publishState,
        long publishLeaseExpiresAt
) {
}
