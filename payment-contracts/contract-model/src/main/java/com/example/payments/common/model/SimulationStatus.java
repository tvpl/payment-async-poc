package com.example.payments.common.model;

/**
 * Lifecycle of a payment simulation as tracked by the API (in Redis).
 *
 * <ul>
 *   <li>{@code PENDING} – accepted by the API, not yet handed to the bus.</li>
 *   <li>{@code SENT_TO_SBUS} – {@code PaymentSimulationRequested} published to Kafka.</li>
 *   <li>{@code PROCESSING} – SBUS acknowledged / Core is working.</li>
 *   <li>{@code COMPLETED} / {@code FAILED} – terminal business outcomes.</li>
 *   <li>{@code TIMEOUT} – the HTTP wait expired; processing may still finish async.</li>
 * </ul>
 */
public enum SimulationStatus {
    PENDING,
    SENT_TO_SBUS,
    PROCESSING,
    COMPLETED,
    FAILED,
    TIMEOUT
}
