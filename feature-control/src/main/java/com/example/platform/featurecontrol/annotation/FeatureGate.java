package com.example.platform.featurecontrol.annotation;

import io.micronaut.aop.Around;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates an HTTP handler behind a feature flag, removing boilerplate: instead of resolving the flag and
 * branching by hand, annotate the method (or controller) and let the interceptor deny the call when
 * the flag is off for the current caller (recognized from the JWT).
 *
 * <pre>{@code
 * @Get("/v2/report")
 * @FeatureGate("reporting-v2")   // 404 unless the caller's flag resolves on
 * Report v2() { ... }
 * }</pre>
 *
 * Optional convenience — requires micronaut-aop + an HTTP server on the classpath; the rest of the lib
 * works without it. See {@link FeatureGateInterceptor}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Around
public @interface FeatureGate {

    /** The flag name to require. */
    String value();

    /** When denied, return 404 (hide the feature) if true, otherwise 403. */
    boolean notFound() default true;
}
