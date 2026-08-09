package com.example.payments.common.events;

/** Canonical event/command type names used in the {@code eventType} field. */
public final class EventTypes {

    public static final String PAYMENT_SIMULATION_REQUESTED = "PaymentSimulationRequested";
    public static final String PROCESS_PAYMENT_SIMULATION_COMMAND = "ProcessPaymentSimulationCommand";
    public static final String CORE_PAYMENT_SIMULATION_RESPONSE = "CorePaymentSimulationResponse";
    public static final String PAYMENT_SIMULATION_COMPLETED = "PaymentSimulationCompleted";
    public static final String PAYMENT_SIMULATION_FAILED = "PaymentSimulationFailed";

    private EventTypes() {
    }
}
