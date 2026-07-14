package com.example.platform.pilot;

import io.micronaut.runtime.Micronaut;

/**
 * Minimal reference consumer of the {@code feature-control} library — the adoption template for the
 * 30+ apps. Shows the two ways an app uses the lib: calling {@code FeatureResolver} directly, and
 * gating a route declaratively with {@code @FeatureGate}. See {@code docs/19-adocao.md}.
 */
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
