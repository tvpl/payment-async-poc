package com.example.platform.featuredemo.web;

import com.example.platform.featurecontrol.model.FeatureDecision;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Set;

/**
 * Uniform demo payload: the {@link FeatureDecision} plus the subject it was evaluated for, so each
 * scenario response makes the "who / what / why" explicit (great for curl walkthroughs).
 */
@Serdeable
public record DemoResponse(
        String scenario,
        FeatureDecision decision,
        @Nullable String userId,
        Set<String> groups,
        String bucketingKey,
        String explanation) {
}
