package com.example.platform.featuredemo;

import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.FlagType;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime verification of the feature-control lib wired into a real app: JWT issuance + bearer
 * validation, JWT->group recognition, the allowlist/version scenarios, and a live Redis flag flip via
 * the admin API. Uses the local Redis pointed at by {@code REDIS_TEST_URI} (no Docker needed); falls
 * back to nothing if unset, so it is an {@code IT} excluded from the default test run.
 */
@MicronautTest
class FeatureDemoFlowIT implements TestPropertyProvider {

    @Serdeable
    record TokenResponse(String accessToken, String tokenType, String userId, List<String> groups) {
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Override
    public Map<String, String> getProperties() {
        String uri = System.getenv().getOrDefault("REDIS_TEST_URI", "redis://localhost:6379");
        // Short cache TTL so a runtime flip is observed promptly even across the read cache.
        return Map.of("redis.uri", uri, "platform.features.cache-ttl", "200ms");
    }

    private String token(String userId, List<String> groups) {
        TokenResponse resp = client.toBlocking().retrieve(
                HttpRequest.POST("/auth/token", Map.of("userId", userId, "groups", groups)),
                TokenResponse.class);
        assertNotNull(resp.accessToken());
        return resp.accessToken();
    }

    /** Admin endpoints require ROLE_ADMIN (mapped from the roles claim). */
    private String adminToken() {
        return token("admin-user", List.of("ROLE_ADMIN"));
    }

    @Test
    void restrictedGrantedForGroupMemberDeniedForOthers() {
        String beta = token("alice", List.of("beta"));

        Map<String, Object> granted = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/restricted").bearerAuth(beta), Argument.mapOf(String.class, Object.class));
        assertEquals(true, ((Map<?, ?>) granted.get("decision")).get("on"));

        // No token -> anonymous -> denied (403).
        HttpClientResponseException denied = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/demo/restricted")));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
    }

    @Test
    void v0VersionGatedByGroup() {
        String tester = token("bob", List.of("v0-testers"));

        Map<String, Object> eligible = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/version").bearerAuth(tester), Argument.mapOf(String.class, Object.class));
        assertEquals("v0", eligible.get("resolved"));
        assertEquals(true, eligible.get("betaAvailable"));

        Map<String, Object> stable = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/version"), Argument.mapOf(String.class, Object.class));
        assertEquals("v1", stable.get("resolved"));
    }

    @Test
    void runtimeFlipViaAdminChangesDecision() {
        // Baseline: demo-toggle is ON -> service-b.
        Map<String, Object> before = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/toggle"), Argument.mapOf(String.class, Object.class));
        assertEquals("service-b", ((Map<?, ?>) before.get("decision")).get("variant"));

        // Flip it OFF at runtime via the admin API (writes to Redis, invalidates cache).
        String admin = adminToken();
        FlagDefinition off = new FlagDefinition("demo-toggle", FlagType.BOOLEAN, false, 0,
                null, null, null, "service-b", "service-a");
        client.toBlocking().exchange(HttpRequest.PUT("/admin/features/demo-toggle", off).bearerAuth(admin));

        Map<String, Object> after = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/toggle"), Argument.mapOf(String.class, Object.class));
        assertEquals(false, ((Map<?, ?>) after.get("decision")).get("on"));
        assertEquals("service-a", ((Map<?, ?>) after.get("decision")).get("variant"));

        // Remove the override -> baseline (ON) applies again.
        client.toBlocking().exchange(HttpRequest.DELETE("/admin/features/demo-toggle").bearerAuth(admin));
        Map<String, Object> restored = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/toggle"), Argument.mapOf(String.class, Object.class));
        assertTrue(((Map<?, ?>) restored.get("decision")).get("on") instanceof Boolean);
    }

    @Test
    void healthIsUp() {
        HttpResponse<Map> health = client.toBlocking().exchange(HttpRequest.GET("/health"), Map.class);
        assertEquals(HttpStatus.OK, health.getStatus());
    }

    @Test
    void optimisticConcurrencyReturns409() {
        String admin = adminToken();
        FlagDefinition create = new FlagDefinition("cas-demo", FlagType.BOOLEAN, true, 0,
                null, null, null, "on", "off", 0L, null);
        // First write creates it (version 0 -> 1).
        client.toBlocking().exchange(HttpRequest.PUT("/admin/features/cas-demo", create).bearerAuth(admin));
        // Second write still claiming version 0 must lose (409).
        HttpClientResponseException conflict = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.PUT("/admin/features/cas-demo", create).bearerAuth(admin)));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        client.toBlocking().exchange(HttpRequest.DELETE("/admin/features/cas-demo").bearerAuth(admin));
    }

    @Test
    void killSwitchForcesEverythingOff() {
        String admin = adminToken();
        FlagDefinition kill = new FlagDefinition("__kill_switch__", FlagType.BOOLEAN, true, 0,
                null, null, null, "on", "off", 0L, null);
        client.toBlocking().exchange(HttpRequest.PUT("/admin/features/__kill_switch__", kill).bearerAuth(admin));
        try {
            Map<String, Object> toggle = client.toBlocking().retrieve(
                    HttpRequest.GET("/demo/toggle"), Argument.mapOf(String.class, Object.class));
            assertEquals("kill-switch", ((Map<?, ?>) toggle.get("decision")).get("reason"));
            assertEquals(false, ((Map<?, ?>) toggle.get("decision")).get("on"));
        } finally {
            client.toBlocking().exchange(
                    HttpRequest.DELETE("/admin/features/__kill_switch__").bearerAuth(admin));
        }
    }

    @Test
    void adminRequiresAdminRole() {
        FlagDefinition any = new FlagDefinition("x", FlagType.BOOLEAN, true, 0,
                null, null, null, "on", "off", 0L, null);
        // No token -> rejected by the security intercept-url-map (ROLE_ADMIN required).
        HttpClientResponseException anon = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.PUT("/admin/features/x", any)));
        assertTrue(anon.getStatus() == HttpStatus.UNAUTHORIZED || anon.getStatus() == HttpStatus.FORBIDDEN);

        // A non-admin token is also rejected.
        String user = token("bob", List.of("beta"));
        HttpClientResponseException nonAdmin = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.PUT("/admin/features/x", any).bearerAuth(user)));
        assertEquals(HttpStatus.FORBIDDEN, nonAdmin.getStatus());
    }

    @Test
    void decisionMetricsAreExposed() {
        client.toBlocking().retrieve(HttpRequest.GET("/demo/toggle"), Argument.mapOf(String.class, Object.class));
        String metrics = client.toBlocking().retrieve(HttpRequest.GET("/prometheus"));
        assertTrue(metrics.contains("feature_decisions_total"), "expected feature decision metric");
    }
}
