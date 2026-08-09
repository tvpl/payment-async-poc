package com.example.payments.coremock;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandaloneBoundaryTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void resolvesPublishedContractsWithoutCrossBoundaryProjectDependency() throws IOException {
        String build = Files.readString(ROOT.resolve("build.gradle"));

        assertTrue(build.contains("com.example.payments:payment-contract-model:${contractsVersion}"));
        assertTrue(build.contains("com.example.payments:payment-contract-avro-apicurio:${contractsVersion}"));
        assertFalse(build.contains("project("));
        assertFalse(build.contains("../common"));
    }

    @Test
    void ownsItsStandaloneBuildAndWrapper() {
        assertTrue(Files.isRegularFile(ROOT.resolve("settings.gradle")));
        assertTrue(Files.isRegularFile(ROOT.resolve("build.gradle")));
        assertTrue(Files.isRegularFile(ROOT.resolve("gradlew")));
        assertTrue(Files.isRegularFile(ROOT.resolve("gradle/wrapper/gradle-wrapper.jar")));
    }

    @Test
    void preservesCanonicalCoreTopicsAndHeaders() {
        assertEquals("payment.simulation.core.command", Topics.CORE_COMMAND);
        assertEquals("payment.simulation.core.response", Topics.CORE_RESPONSE);
        assertEquals("x-request-id", Headers.REQUEST_ID);
        assertEquals("traceparent", Headers.TRACEPARENT);
    }
}
