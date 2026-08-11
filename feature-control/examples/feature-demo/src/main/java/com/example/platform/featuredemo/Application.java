package com.example.platform.featuredemo;

import io.micronaut.runtime.Micronaut;

/**
 * Runnable showcase of the {@code feature-control} library: one endpoint per scenario (feature
 * toggle, A/B percentage, restricted allowlist, API v0 versioning, Kafka topic A/B routing) plus a
 * dev JWT issuer and a runtime flag-flip admin. See {@code docs/16-feature-control-lib.md}.
 */
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
