package com.example.payments.sbus.kafka;

/** Signals a permanently un-processable message that must go straight to the DLQ. */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
