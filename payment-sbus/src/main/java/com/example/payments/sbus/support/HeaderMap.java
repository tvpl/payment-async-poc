package com.example.payments.sbus.support;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.Headers;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the technical header map persisted on outbox rows and replayed to Kafka. */
public final class HeaderMap {

    private HeaderMap() {
    }

    public static Map<String, String> from(EventEnvelope<?> env, String traceparent) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(Headers.REQUEST_ID, env.requestId());
        headers.put(Headers.CORRELATION_ID, env.correlationId());
        if (env.causationId() != null) {
            headers.put(Headers.CAUSATION_ID, env.causationId());
        }
        headers.put(Headers.EVENT_TYPE, env.eventType());
        headers.put(Headers.EVENT_VERSION, env.eventVersion());
        if (traceparent != null) {
            headers.put(Headers.TRACEPARENT, traceparent);
        }
        return headers;
    }
}
