package com.example.payments.sbus.domain;

/**
 * Processing state of a simulation inside the SBUS.
 *
 * <p>These are exactly the values allowed by the table's CHECK constraint
 * ({@code V7__guard_terminal_transition.sql}). A constant that is not in that list would compile
 * fine and then fail at INSERT time, so this enum must not drift from the migration.
 */
public enum SbusMessageStatus {
    PROCESSING,
    COMPLETED,
    FAILED
}
