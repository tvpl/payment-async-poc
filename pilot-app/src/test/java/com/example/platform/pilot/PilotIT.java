package com.example.platform.pilot;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.security.token.generator.TokenGenerator;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the adoption template end-to-end against a local Redis at {@code localhost:6379}: the
 * imperative resolver call returns a variant, and the {@code @FeatureGate} route is visible to the
 * beta group but 404 to everyone else.
 */
@MicronautTest
class PilotIT {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TokenGenerator tokenGenerator;

    private String token(List<String> groups) {
        return tokenGenerator.generateToken(Map.of(
                "sub", "user-1", "roles", groups, "groups", groups)).orElseThrow();
    }

    @Test
    void checkoutReturnsAVariant() {
        Map<?, ?> body = client.toBlocking().retrieve(
                HttpRequest.GET("/pilot/checkout").header("X-Anon-Id", "u-1"), Map.class);
        assertNotNull(body.get("engine"));
        assertTrue("v1".equals(body.get("variant")) || "v2".equals(body.get("variant")));
    }

    @Test
    void featureGateHidesBetaFromNonMembers() {
        Map<?, ?> ok = client.toBlocking().retrieve(
                HttpRequest.GET("/pilot/beta").bearerAuth(token(List.of("beta"))), Map.class);
        assertEquals(true, ok.get("ok"));

        HttpClientResponseException hidden = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/pilot/beta")));
        assertEquals(HttpStatus.NOT_FOUND, hidden.getStatus());
    }
}
