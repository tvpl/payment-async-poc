package com.example.payments.common.events;

/** Kafka topic names. Messages are keyed by {@code requestId} for ordering per simulation. */
public final class Topics {

    public static final String REQUESTED = "payment.simulation.requested";
    public static final String CORE_COMMAND = "payment.simulation.core.command";
    public static final String CORE_RESPONSE = "payment.simulation.core.response";
    public static final String COMPLETED = "payment.simulation.completed";
    public static final String FAILED = "payment.simulation.failed";
    public static final String DLQ = "payment.simulation.dlq";

    private Topics() {
    }
}
