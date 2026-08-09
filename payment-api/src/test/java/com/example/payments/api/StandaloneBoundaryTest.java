package com.example.payments.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandaloneBoundaryTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void resolvesPublishedDependenciesWithoutCrossBoundaryProjects() throws IOException {
        String build = Files.readString(ROOT.resolve("build.gradle"));

        assertTrue(build.contains("com.example.payments:payment-contract-model:${contractsVersion}"));
        assertTrue(build.contains("com.example.payments:payment-contract-avro-apicurio:${contractsVersion}"));
        assertTrue(build.contains("com.example.platform:feature-control:${featureControlVersion}"));
        assertFalse(build.contains("project("));
        assertFalse(build.contains("../common"));
    }

    @Test
    void ownsStandaloneBuildAndWrapper() {
        assertTrue(Files.isRegularFile(ROOT.resolve("settings.gradle")));
        assertTrue(Files.isRegularFile(ROOT.resolve("build.gradle")));
        assertTrue(Files.isRegularFile(ROOT.resolve("gradlew")));
        assertTrue(Files.isRegularFile(ROOT.resolve("gradle/wrapper/gradle-wrapper.jar")));
    }
}
