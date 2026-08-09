package com.example.payments.common.kafka;

/** Raised when the bounded Avro codec capacity cannot be acquired in time. */
public final class AvroCodecUnavailableException extends RuntimeException {

    public AvroCodecUnavailableException(String message) {
        super(message);
    }

    public AvroCodecUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
