package com.example.platform.featuredemo.web;

import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.context.JwtFeatureContextFactory;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Singleton;

/**
 * Builds a {@link FeatureContext} for demo requests from the (optional) JWT plus an {@code X-Anon-Id}
 * used to make anonymous A/B bucketing sticky per caller when there is no user.
 */
@Singleton
public class DemoContexts {

    public FeatureContext from(@Nullable Authentication authentication, @Nullable String anonId) {
        return JwtFeatureContextFactory.from(authentication, anonId);
    }
}
