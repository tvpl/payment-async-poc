package com.example.platform.featurecontrol.annotation;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

/**
 * Maps {@link FeatureDisabledException} to 404 (hide the feature) or 403 (reject), keeping the
 * {@link FeatureGate} ergonomics self-contained. Only wired when an HTTP server is present.
 */
@Singleton
@Requires(classes = {FeatureDisabledException.class, ExceptionHandler.class})
public class FeatureDisabledExceptionHandler
        implements ExceptionHandler<FeatureDisabledException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, FeatureDisabledException exception) {
        HttpStatus status = exception.isNotFound() ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN;
        return HttpResponse.status(status);
    }
}
