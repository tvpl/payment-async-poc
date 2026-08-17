package com.example.payments.api.config;

import com.redis.testcontainers.RedisContainer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionProfileIT {

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    private EmbeddedServer server;
    private HttpClient client;

    @BeforeAll
    void start() {
        KAFKA.start();
        REDIS.start();
        APICURIO.start();
        server = ApplicationContext.run(EmbeddedServer.class, validProductionProperties(), "prod");
        client = HttpClient.create(server.getURL());
    }

    @AfterAll
    void stop() {
        client.close();
        server.close();
    }

    @Test
    void productionProfileUsesAsymmetricClaimsValidationAndNoSharedSecret() throws IOException {
        String production = Files.readString(Path.of("src/main/resources/application-prod.yml"));

        assertTrue(production.contains("jwks:"));
        assertTrue(production.contains("issuer: ${JWT_ISSUER}"));
        assertTrue(production.contains("audience: ${JWT_AUDIENCE}"));
        assertTrue(production.contains("expiration: true"));
        assertTrue(production.contains("not-before: true"));
        assertFalse(production.contains("secret:"));
    }

    @Test
    void invalidProductionIdentityConfigurationFailsContextStartup() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                ApplicationContext.builder()
                        .environments("prod")
                        .properties(Map.ofEntries(
                                Map.entry("micronaut.server.enabled", false),
                                Map.entry("kafka.enabled", false),
                                Map.entry("micronaut.scheduling.enabled", false),
                                Map.entry("micronaut.security.token.jwt.signatures.jwks.idp.url",
                                        "https://idp.example.test/jwks"),
                                Map.entry("micronaut.security.token.jwt.claims-validators.issuer",
                                        "https://idp.example.test/"),
                                Map.entry("micronaut.security.token.jwt.claims-validators.audience", " "),
                                Map.entry("payment.security.clock-skew", "0s"),
                                Map.entry("payment.security.enabled", true),
                                Map.entry("payment.security.api-keys", List.of("prod-issued-key"))))
                        .start());

        assertTrue(messages(failure).contains("JWT audience is required in production"));
    }

    @Test
    void devTokenRouteAbsentInProduction() {
        HttpClientResponseException failure = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.POST("/auth/token",
                        Map.of("userId", "someone", "groups", List.of()))));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatus());
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    private static Map<String, Object> validProductionProperties() {
        return Map.ofEntries(
                Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                Map.entry("redis.uri", REDIS.getRedisURI()),
                Map.entry("apicurio.registry.url", registryUrl()),
                Map.entry("otel.traces.exporter", "none"),
                Map.entry("micronaut.security.token.jwt.signatures.jwks.idp.url",
                        "https://idp.example.test/.well-known/jwks.json"),
                Map.entry("micronaut.security.token.jwt.claims-validators.issuer", "https://idp.example.test/"),
                Map.entry("micronaut.security.token.jwt.claims-validators.audience", "payment-api"),
                Map.entry("payment.security.clock-skew", "0s"),
                Map.entry("payment.security.enabled", true),
                // SEC-04: production accepts only sha256:<hex> - sha256("prod-issued-key") below - never
                // the plaintext credential in config. Callers still authenticate with the raw key.
                Map.entry("payment.security.api-keys",
                        List.of("sha256:5c7d1219dbfe41a8993897b29976bfa78408bb496727d7ad1794800cb1982eef")),
                // sha256("prod-issued-key"): the binding for the sole production key above (T4/TEN-01-03).
                Map.entry("payment.security.tenants.5c7d1219dbfe41a8993897b29976bfa78408bb496727d7ad1794800cb1982eef",
                        List.of("tenant-a")));
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }
}
