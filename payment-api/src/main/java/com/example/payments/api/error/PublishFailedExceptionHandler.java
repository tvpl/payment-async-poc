package com.example.payments.api.error;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

/** Maps a failed publish (Kafka unavailable) to 503 with a problem+json body. */
@Produces(Problem.MEDIA_TYPE)
@Singleton
@Requires(classes = {PublishFailedException.class, ExceptionHandler.class})
public class PublishFailedExceptionHandler
        implements ExceptionHandler<PublishFailedException, HttpResponse<Problem>> {

    @Override
    public HttpResponse<Problem> handle(HttpRequest request, PublishFailedException exception) {
        return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Problem.of(503, "Upstream publish failed",
                        "Could not enqueue the simulation request. Please retry."));
    }
}
