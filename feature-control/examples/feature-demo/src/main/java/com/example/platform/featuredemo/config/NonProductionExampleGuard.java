package com.example.platform.featuredemo.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;

/**
 * Refuses startup under the {@code prod} environment (SEC-01, SEC-02). {@code feature-demo} is
 * explicitly {@code NON_PRODUCTION} (AD-005) — every controller here exists to demonstrate the
 * {@code feature-control} library (a dev-only JWT issuer, an unauthenticated toggle/gate demo, an
 * admin endpoint with no real operator identity behind it). There is no valid production
 * configuration for this app, so unlike a real service's {@code ProductionAcceptanceGuard} (which
 * validates config and starts), this guard fails startup unconditionally: no demo/admin route can
 * ever reach the bean graph or HTTP surface while {@code prod} is active.
 */
@Context
@Requires(env = "prod")
public final class NonProductionExampleGuard {

    public NonProductionExampleGuard() {
        throw new ConfigurationException(
                "feature-demo is a NON_PRODUCTION example (AD-005) and must never start with the"
                        + " 'prod' environment active");
    }
}
