package com.example.payments.sbus.domain;

/** Publication state of an outbox row. */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
