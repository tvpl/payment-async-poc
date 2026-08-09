package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;

import java.util.List;

/**
 * Refuses production startup unless {@code POST /jobs} actually has the three gates RED-08 names:
 * authentication, idempotency and an admission limit.
 *
 * <p>RED-08 allows exactly two states — a boundary with all three enabled, or one explicitly
 * classified as a non-production example. Booting production with a gate missing would be the
 * unclaimed middle, so it fails here instead of at the first unauthenticated request.
 */
@Context
@Requires(env = "prod")
public final class ProductionAcceptanceGuard {

    public ProductionAcceptanceGuard(AsyncSecurityProperties security, AsyncRedisProperties props) {
        validate(security.isEnabled(), security.getApiKeys(),
                props.isIdempotencyRequired(), props.getAdmissionLimitPerSec());
    }

    static void validate(boolean authEnabled, List<String> apiKeys,
                         boolean idempotencyRequired, int admissionLimitPerSec) {
        if (!authEnabled) {
            throw new ConfigurationException(
                    "async.redis.security.enabled must be true in production");
        }
        if (apiKeys == null || apiKeys.isEmpty()) {
            throw new ConfigurationException(
                    "async.redis.security.api-keys is required in production");
        }
        for (String key : apiKeys) {
            if (key == null || key.isBlank()
                    || AsyncSecurityProperties.DEV_DEFAULT_API_KEY.equals(key)) {
                throw new ConfigurationException(
                        "async.redis.security.api-keys must not be blank or the development default"
                                + " in production");
            }
        }
        if (!idempotencyRequired) {
            throw new ConfigurationException(
                    "async.redis.idempotency-required must be true in production");
        }
        if (admissionLimitPerSec <= 0) {
            throw new ConfigurationException(
                    "async.redis.admission-limit-per-sec must be greater than zero in production");
        }
    }
}
