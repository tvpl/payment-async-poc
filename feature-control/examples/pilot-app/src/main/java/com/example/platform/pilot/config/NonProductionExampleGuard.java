package com.example.platform.pilot.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;

/**
 * Refuses startup under the {@code prod} environment (SEC-01, SEC-02). {@code pilot-app} is
 * explicitly {@code NON_PRODUCTION} (AD-005) — it exists to demonstrate {@code feature-control}
 * adoption, not to serve real traffic. See {@code feature-demo}'s
 * {@code com.example.platform.featuredemo.config.NonProductionExampleGuard} for the same guard on
 * the sibling example.
 */
@Context
@Requires(env = "prod")
public final class NonProductionExampleGuard {

    public NonProductionExampleGuard() {
        throw new ConfigurationException(
                "pilot-app is a NON_PRODUCTION example (AD-005) and must never start with the"
                        + " 'prod' environment active");
    }
}
