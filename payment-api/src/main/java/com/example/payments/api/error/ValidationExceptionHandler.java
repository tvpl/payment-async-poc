package com.example.payments.api.error;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.validation.exceptions.ConstraintExceptionHandler;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.util.stream.Collectors;

/** Renders bean-validation failures as 422-style 400 problem+json (replaces the default). */
@Produces(Problem.MEDIA_TYPE)
@Singleton
@Replaces(ConstraintExceptionHandler.class)
public class ValidationExceptionHandler
        implements ExceptionHandler<ConstraintViolationException, HttpResponse<Problem>> {

    @Override
    public HttpResponse<Problem> handle(HttpRequest request, ConstraintViolationException exception) {
        String detail = exception.getConstraintViolations().stream()
                .map(this::format)
                .collect(Collectors.joining("; "));
        return HttpResponse.status(HttpStatus.BAD_REQUEST)
                .body(Problem.of(400, "Invalid request", detail.isBlank() ? "Validation failed" : detail));
    }

    private String format(ConstraintViolation<?> v) {
        String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
        return path + " " + v.getMessage();
    }
}
