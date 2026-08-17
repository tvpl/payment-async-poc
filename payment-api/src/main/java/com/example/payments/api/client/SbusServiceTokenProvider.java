package com.example.payments.api.client;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Mints (dev) or supplies (prod) the bearer token the Edge presents as its own service identity
 * when calling the SBUS internal status endpoint (SEC-05).
 *
 * <p>{@link #currentToken()} never throws: an absent credential returns {@link
 * Optional#empty()} so the call goes out unauthenticated and fails/degrades exactly like any
 * other SBUS outage (see {@code SbusStatusGateway}), rather than blocking the caller on a
 * signing error.
 */
@Singleton
public class SbusServiceTokenProvider {

    private static final String ROLE_CLAIM = "roles";
    private static final String SERVICE_ROLE = "ROLE_PAYMENT_API";
    /** Re-mint this far ahead of expiry so a signed token is never presented near-expired. */
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(30);

    private final SbusCredentialProperties properties;
    private volatile MintedToken cached;

    public SbusServiceTokenProvider(SbusCredentialProperties properties) {
        this.properties = properties;
    }

    public Optional<String> currentToken() {
        if (hasText(properties.getToken())) {
            return Optional.of(properties.getToken());
        }
        if (hasText(properties.getSecret())) {
            return Optional.of(signedToken());
        }
        return Optional.empty();
    }

    private synchronized String signedToken() {
        Instant now = Instant.now();
        MintedToken current = cached;
        if (current != null && current.expiresAt().isAfter(now.plus(REFRESH_SKEW))) {
            return current.value();
        }
        Instant expiresAt = now.plus(properties.getTtl());
        String value = sign(now, expiresAt);
        cached = new MintedToken(value, expiresAt);
        return value;
    }

    private String sign(Instant issuedAt, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(properties.getSubject())
                    .claim(ROLE_CLAIM, List.of(SERVICE_ROLE))
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(properties.getSecret().getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to sign the SBUS service credential", e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record MintedToken(String value, Instant expiresAt) {
    }
}
