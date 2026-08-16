package com.example.payments.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard technical envelope wrapping every event/command exchanged on Kafka.
 *
 * <p>The envelope carries the correlation metadata that lets us trace a single
 * payment simulation end to end (HTTP -&gt; API -&gt; Kafka -&gt; SBUS -&gt; Core -&gt; back),
 * independently of the business {@code payload}.
 *
 * <p>{@code eventVersion} enables backward/forward compatible evolution. The
 * payload is JSON today; the same envelope can later carry Avro/Protobuf bytes
 * with a Schema Registry without changing consumers' correlation logic.
 *
 * @param <T> the business payload type
 */
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String requestId,
        String correlationId,
        String causationId,
        String traceId,
        String source,
        String tenantId,
        T payload
) {

    public static final String CURRENT_VERSION = "1.0";

    /**
     * Builds a fresh envelope with no tenant scope ({@code tenantId} defaults to {@code ""}),
     * generating {@code eventId} and stamping {@code occurredAt}. {@code causationId} should be
     * the {@code eventId} of the event that caused this one (or the {@code requestId} at the very
     * start). Kept for callers that predate tenant scoping; new callers that know their tenant
     * should use {@link #create(String, String, String, String, String, String, String, Object)}.
     */
    public static <T> EventEnvelope<T> create(
            String eventType,
            String requestId,
            String correlationId,
            String causationId,
            String traceId,
            String source,
            T payload) {
        return create(eventType, requestId, correlationId, causationId, traceId, source, "", payload);
    }

    /**
     * Builds a fresh envelope scoped to {@code tenantId}, generating {@code eventId} and stamping
     * {@code occurredAt}. {@code causationId} should be the {@code eventId} of the event that
     * caused this one (or the {@code requestId} at the very start).
     */
    public static <T> EventEnvelope<T> create(
            String eventType,
            String requestId,
            String correlationId,
            String causationId,
            String traceId,
            String source,
            String tenantId,
            T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                CURRENT_VERSION,
                Instant.now(),
                requestId,
                correlationId,
                causationId,
                traceId,
                source,
                tenantId,
                payload);
    }

    /**
     * Derives a new envelope caused by this one, keeping correlation identity and tenant scope
     * but setting {@code causationId} to this event's id and swapping the payload.
     */
    public <R> EventEnvelope<R> deriveAs(String newEventType, String source, R newPayload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                newEventType,
                CURRENT_VERSION,
                Instant.now(),
                requestId,
                correlationId,
                this.eventId,
                traceId,
                source,
                this.tenantId,
                newPayload);
    }
}
