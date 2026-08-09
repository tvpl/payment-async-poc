package com.example.payments.api.error;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

/** Maps a reused idempotency key with a divergent payload to 409 with a problem+json body. */
@Produces(Problem.MEDIA_TYPE)
@Singleton
@Requires(classes = {IdempotencyConflictException.class, ExceptionHandler.class})
public class IdempotencyConflictExceptionHandler
        implements ExceptionHandler<IdempotencyConflictException, HttpResponse<Problem>> {

    @Override
    public HttpResponse<Problem> handle(HttpRequest request, IdempotencyConflictException exception) {
        return HttpResponse.status(HttpStatus.CONFLICT)
                .body(Problem.of(409, "Idempotency Conflict",
                        "Idempotency-Key was already used with a different request payload."));
    }
}
