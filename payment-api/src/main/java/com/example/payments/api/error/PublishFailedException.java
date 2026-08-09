package com.example.payments.api.error;

/** Raised when the API cannot publish the request to Kafka (maps to HTTP 503). */
public class PublishFailedException extends RuntimeException {

    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
