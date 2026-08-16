package com.example.payments.api.error;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a Redis-store outage to 503 with a problem+json body. The cause (host, port, Lettuce
 * connection text) goes to the log only — never to the response, which would otherwise hand an
 * anonymous caller the API's internal network topology.
 */
@Produces(Problem.MEDIA_TYPE)
@Singleton
@Requires(classes = {StoreUnavailableException.class, ExceptionHandler.class})
public class StoreUnavailableExceptionHandler
        implements ExceptionHandler<StoreUnavailableException, HttpResponse<Problem>> {

    private static final Logger LOG = LoggerFactory.getLogger(StoreUnavailableExceptionHandler.class);

    @Override
    public HttpResponse<Problem> handle(HttpRequest request, StoreUnavailableException exception) {
        LOG.error("Redis store unavailable: {}", exception.getMessage(), exception.getCause());
        return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(Problem.of(503, "Service Unavailable", "Could not reach the shared store. Please retry."));
    }
}
