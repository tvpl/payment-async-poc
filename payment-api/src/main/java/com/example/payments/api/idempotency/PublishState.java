package com.example.payments.api.idempotency;

/**
 * Whether the request that owns an idempotency reservation ever got its
 * {@code PaymentSimulationRequested} onto Kafka (PAY-03).
 *
 * <p>The state lives on the reservation rather than on the status entry because the
 * reservation is guaranteed to outlive it ({@code idempotency-ttl >= status-ttl}), so a
 * retry can always tell "never published" from "published, status already expired".
 */
public enum PublishState {

    /** Reserved; an attempt is in flight and holds the publish lease. */
    PENDING_PUBLISH,

    /** The Kafka broker acknowledged the request event. */
    PUBLISHED,

    /** The publish attempt failed. The identity is retained and immediately resumable. */
    PUBLISH_FAILED
}
