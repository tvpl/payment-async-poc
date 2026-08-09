package com.example.payments.sbus.domain;

/** Processing state of a simulation inside the SBUS. */
public enum SbusMessageStatus {
    RECEIVED,
    PROCESSING,
    COMPLETED,
    FAILED
}
