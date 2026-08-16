package com.example.payments.contracts.compatibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaCompatibilityContractTest {

    private static final Path SCHEMAS = Path.of("../schemas").toAbsolutePath().normalize();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void currentSchemasAreFullTransitiveAgainstEveryVersion() throws Exception {
        assertEquals(Set.of(), Set.copyOf(FullTransitiveCompatibility.verify(SCHEMAS, SCHEMAS.resolve("history"))));
    }

    @Test
    void optionalFieldWithDefaultIsFullCompatible() {
        Schema previous = schema("{\"type\":\"record\",\"name\":\"Payment\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"string\"}]}");
        Schema candidate = schema("{\"type\":\"record\",\"name\":\"Payment\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"string\"},"
                + "{\"name\":\"note\",\"type\":[\"null\",\"string\"],\"default\":null}]}");

        assertTrue(FullTransitiveCompatibility.isFullCompatible(previous, candidate));
    }

    @Test
    void requiredFieldRemovalIsRejected() {
        Schema previous = schema("{\"type\":\"record\",\"name\":\"Payment\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"string\"},"
                + "{\"name\":\"amount\",\"type\":\"string\"}]}");
        Schema candidate = schema("{\"type\":\"record\",\"name\":\"Payment\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"string\"}]}");

        assertFalse(FullTransitiveCompatibility.isFullCompatible(previous, candidate));
    }

    @Test
    void incompatibleTypeMutationIsRejected() {
        Schema previous = schema("{\"type\":\"record\",\"name\":\"Payment\",\"fields\":["
                + "{\"name\":\"amount\",\"type\":\"string\"}]}");
        Schema candidate = schema("{\"type\":\"record\",\"name\":\"Payment\",\"fields\":["
                + "{\"name\":\"amount\",\"type\":\"long\"}]}");

        assertFalse(FullTransitiveCompatibility.isFullCompatible(previous, candidate));
    }

    @Test
    void breakingChangeRequiresNewMajorArtifactTopicAndCoexistence() {
        var previous = new FullTransitiveCompatibility.ReleaseIdentity(
                1, "PaymentSimulationRequested", "payment.simulation.requested", true);

        assertFalse(FullTransitiveCompatibility.permitsBreakingChange(previous,
                new FullTransitiveCompatibility.ReleaseIdentity(
                        1, "PaymentSimulationRequestedV2", "payment.simulation.requested.v2", true)));
        assertFalse(FullTransitiveCompatibility.permitsBreakingChange(previous,
                new FullTransitiveCompatibility.ReleaseIdentity(
                        2, "PaymentSimulationRequested", "payment.simulation.requested.v2", true)));
        assertFalse(FullTransitiveCompatibility.permitsBreakingChange(previous,
                new FullTransitiveCompatibility.ReleaseIdentity(
                        2, "PaymentSimulationRequestedV2", "payment.simulation.requested", true)));
        assertFalse(FullTransitiveCompatibility.permitsBreakingChange(previous,
                new FullTransitiveCompatibility.ReleaseIdentity(
                        2, "PaymentSimulationRequestedV2", "payment.simulation.requested.v2", false)));
        assertTrue(FullTransitiveCompatibility.permitsBreakingChange(previous,
                new FullTransitiveCompatibility.ReleaseIdentity(
                        2, "PaymentSimulationRequestedV2", "payment.simulation.requested.v2", true)));
    }

    @Test
    void manifestMapsEveryEventTypeTopicSchemaClassAndHeader() throws Exception {
        JsonNode manifest = objectMapper.readTree(SCHEMAS.resolve("manifest.json").toFile());
        Map<String, List<String>> events = StreamSupport.stream(manifest.get("events").spliterator(), false)
                .collect(Collectors.toMap(
                        node -> node.get("eventType").asText(),
                        node -> List.of(
                                node.get("eventVersion").asText(),
                                node.get("artifactId").asText(),
                                node.get("topic").asText(),
                                node.get("schema").asText(),
                                node.get("className").asText()
                        )
                ));
        Set<String> headers = StreamSupport.stream(manifest.get("headers").spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toSet());

        assertEquals("1.0.0", manifest.get("contractVersion").asText());
        assertEquals("FULL_TRANSITIVE", manifest.get("compatibility").asText());
        assertEquals("payments", manifest.get("registryGroupId").asText());
        assertEquals(Map.of(
                "PaymentSimulationRequested", List.of(
                        "1.0", "PaymentSimulationRequested", "payment.simulation.requested",
                        "PaymentSimulationRequested.avsc",
                        "com.example.payments.common.avro.PaymentSimulationRequested"),
                "ProcessPaymentSimulationCommand", List.of(
                        "1.0", "ProcessPaymentSimulationCommand", "payment.simulation.core.command",
                        "ProcessPaymentSimulationCommand.avsc",
                        "com.example.payments.common.avro.ProcessPaymentSimulationCommand"),
                "CorePaymentSimulationResponse", List.of(
                        "1.0", "CorePaymentSimulationResponse", "payment.simulation.core.response",
                        "CorePaymentSimulationResponse.avsc",
                        "com.example.payments.common.avro.CorePaymentSimulationResponse"),
                "PaymentSimulationCompleted", List.of(
                        "1.0", "PaymentSimulationCompleted", "payment.simulation.completed",
                        "PaymentSimulationCompleted.avsc",
                        "com.example.payments.common.avro.PaymentSimulationCompleted"),
                "PaymentSimulationFailed", List.of(
                        "1.0", "PaymentSimulationFailed", "payment.simulation.failed",
                        "PaymentSimulationFailed.avsc",
                        "com.example.payments.common.avro.PaymentSimulationFailed")
        ), events);
        assertEquals(11, manifest.get("headers").size());
        assertEquals(Set.of(
                "x-request-id", "x-correlation-id", "x-causation-id", "Idempotency-Key",
                "x-event-type", "x-event-version", "traceparent", "x-retry-attempt",
                "x-retry-not-before", "x-orig-topic", "x-tenant-id"
        ), headers);
    }

    @Test
    void policyDisablesProductionAutoRegistrationAndRequiresDryRun() throws Exception {
        JsonNode policy = objectMapper.readTree(SCHEMAS.resolve("compatibility-policy.json").toFile());

        assertEquals("FULL_TRANSITIVE", policy.get("mode").asText());
        assertTrue(policy.get("dryRun").asBoolean());
        assertFalse(policy.get("productionAutoRegister").asBoolean());
        assertTrue(policy.get("breakingChange").get("requiresHigherMajor").asBoolean());
        assertTrue(policy.get("breakingChange").get("requiresNewArtifactId").asBoolean());
        assertTrue(policy.get("breakingChange").get("requiresNewTopic").asBoolean());
        assertTrue(policy.get("breakingChange").get("requiresCoexistence").asBoolean());
        assertTrue(policy.get("breakingChange").get("requiresAdr").asBoolean());
    }

    private static Schema schema(String json) {
        return new Schema.Parser().parse(json);
    }

}
