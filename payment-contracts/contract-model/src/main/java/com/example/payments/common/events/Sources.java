package com.example.payments.common.events;

/** Logical service names used in the envelope {@code source} field. */
public final class Sources {

    public static final String API = "payment-simulation-api";
    public static final String SBUS = "payment-sbus";
    public static final String CORE = "payment-core-mock";

    private Sources() {
    }
}
