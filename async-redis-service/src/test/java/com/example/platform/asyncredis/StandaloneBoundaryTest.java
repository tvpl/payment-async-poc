package com.example.platform.asyncredis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guard for ORG-02/ORG-03/ORG-07: this boundary builds from its own root, owns its build
 * files and wrapper, and neither its build nor its sources reach into another root.
 */
class StandaloneBoundaryTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void ownsStandaloneBuildAndWrapper() {
        assertTrue(Files.isRegularFile(ROOT.resolve("settings.gradle")));
        assertTrue(Files.isRegularFile(ROOT.resolve("build.gradle")));
        assertTrue(Files.isRegularFile(ROOT.resolve("gradle.properties")));
        assertTrue(Files.isRegularFile(ROOT.resolve("gradlew")));
        assertTrue(Files.isRegularFile(ROOT.resolve("gradle/wrapper/gradle-wrapper.jar")));
        assertTrue(Files.isRegularFile(ROOT.resolve("gradle/wrapper/gradle-wrapper.properties")));
    }

    @Test
    void buildDeclaresNoCrossRootDependency() throws IOException {
        String build = Files.readString(ROOT.resolve("build.gradle"));

        assertFalse(build.contains("project("), "no project() dependency on another root");
        assertFalse(build.contains(".."), "no path escaping this root");
    }

    @Test
    void buildDeclaresNoKafkaPostgresOrSharedCommonDependency() throws IOException {
        String build = Files.readString(ROOT.resolve("build.gradle"));

        assertFalse(build.contains("kafka"), "this boundary is Redis-only (ORG-07)");
        assertFalse(build.contains("postgres"), "this boundary is Redis-only (ORG-07)");
        assertFalse(build.contains("avro"), "this boundary carries no contract machinery");
        assertFalse(build.contains("payment-contract"), "this boundary publishes and consumes no contract");
        assertFalse(build.contains("com.example.payments"), "no dependency on another boundary's artifacts");
    }

    @Test
    void sourcesImportNothingFromAnotherBoundary() throws IOException {
        try (var paths = Files.walk(ROOT.resolve("src"))) {
            List<Path> offenders = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(StandaloneBoundaryTest::importsForeignPackage)
                    .toList();

            assertTrue(offenders.isEmpty(), "sources outside this boundary's package: " + offenders);
        }
    }

    private static boolean importsForeignPackage(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .filter(line -> line.startsWith("import "))
                    .anyMatch(line -> line.contains("com.example.payments")
                            || line.contains("com.example.platform.featurecontrol"));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
