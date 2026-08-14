package com.example.payments.sbus.kafka;

/**
 * Signals a transient connectivity failure talking to the Schema Registry while decoding a
 * record (AUD-09) — distinct from {@link PoisonMessageException}: the payload itself may well be
 * perfectly valid, the registry is just unreachable right now. A plain {@link RuntimeException}
 * so it flows through the existing retry path (dead-lettering it would drop a valid payment
 * whenever the registry restarts).
 */
public class RegistryUnavailableException extends RuntimeException {

    public RegistryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
