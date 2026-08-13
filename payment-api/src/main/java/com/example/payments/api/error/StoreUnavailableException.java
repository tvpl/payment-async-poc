package com.example.payments.api.error;

/**
 * Raised when the shared Redis store cannot be reached (maps to HTTP 503). The cause carries the
 * real Lettuce/Redis failure for the log; it never reaches the HTTP response body — see
 * {@link StoreUnavailableExceptionHandler}.
 */
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
