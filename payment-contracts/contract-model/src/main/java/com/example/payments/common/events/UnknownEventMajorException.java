package com.example.payments.common.events;

/**
 * Raised when an {@link EventEnvelope#eventVersion()} carries a major version this consumer
 * does not know how to read (API-02). Callers route this to poison/DLQ handling instead of
 * processing the event silently.
 */
public class UnknownEventMajorException extends RuntimeException {

    public UnknownEventMajorException(String eventVersion) {
        super("Unknown event major version: " + eventVersion);
    }
}
