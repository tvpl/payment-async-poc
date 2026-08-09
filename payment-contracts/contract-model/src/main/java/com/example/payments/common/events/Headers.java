package com.example.payments.common.events;

/**
 * Technical header names propagated across HTTP and Kafka. {@code traceparent}
 * follows the W3C Trace Context spec so OpenTelemetry can stitch spans together.
 */
public final class Headers {

    public static final String REQUEST_ID = "x-request-id";
    public static final String CORRELATION_ID = "x-correlation-id";
    public static final String CAUSATION_ID = "x-causation-id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String EVENT_TYPE = "x-event-type";
    public static final String EVENT_VERSION = "x-event-version";
    public static final String TRACEPARENT = "traceparent";

    /** Retry-topic bookkeeping headers. */
    public static final String RETRY_ATTEMPT = "x-retry-attempt";
    public static final String RETRY_NOT_BEFORE = "x-retry-not-before";
    public static final String ORIGIN_TOPIC = "x-orig-topic";

    private Headers() {
    }
}
