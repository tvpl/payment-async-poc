package com.example.platform.featurecontrol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guard for ORG-02/ORG-04/ORG-05/MIG-02: this boundary (library + its two NON_PRODUCTION
 * examples) builds from its own root, owns its build files and wrapper, and no source or build script
 * anywhere under it reaches into another boundary. Examples may depend on {@code library} within this
 * same root (design.md 2.2); nothing here may reach outside {@code feature-control/}.
 *
 * <p>Gradle runs this project's tests with the working directory set to this module ({@code library}),
 * so the boundary root is one level up.
 */
class StandaloneBoundaryTest {

    private static final Path LIBRARY_ROOT = Path.of("").toAbsolutePath();
    private static final Path BOUNDARY_ROOT = LIBRARY_ROOT.getParent();

    @Test
    void ownsStandaloneBuildAndWrapper() {
        assertTrue(Files.isRegularFile(BOUNDARY_ROOT.resolve("settings.gradle")));
        assertTrue(Files.isRegularFile(BOUNDARY_ROOT.resolve("build.gradle")));
        assertTrue(Files.isRegularFile(BOUNDARY_ROOT.resolve("gradle.properties")));
        assertTrue(Files.isRegularFile(BOUNDARY_ROOT.resolve("gradlew")));
        assertTrue(Files.isRegularFile(BOUNDARY_ROOT.resolve("gradle/wrapper/gradle-wrapper.jar")));
        assertTrue(Files.isRegularFile(BOUNDARY_ROOT.resolve("gradle/wrapper/gradle-wrapper.properties")));
    }

    @Test
    void libraryPublishesAndDeclaresNoCrossBoundaryDependency() throws IOException {
        String build = Files.readString(LIBRARY_ROOT.resolve("build.gradle"));

        assertTrue(build.contains("maven-publish"), "library must be a published Maven artifact (ORG-04)");
        assertFalse(build.contains("project("), "library depends on no other Gradle project (ORG-05)");
        assertFalse(build.contains("com.example.payments"), "library knows nothing about the payment flow");
        assertFalse(build.contains("com.example.platform.asyncredis"),
                "library depends on no other boundary's artifact");
    }

    @Test
    void examplesDependOnlyOnTheLocalLibraryProjectAndPublishNothing() throws IOException {
        for (String example : List.of("examples/feature-demo", "examples/pilot-app")) {
            String build = Files.readString(BOUNDARY_ROOT.resolve(example).resolve("build.gradle"));

            assertTrue(build.contains("project(':feature-control')"),
                    example + " depends on the boundary-local library project, not a cross-boundary one");
            assertFalse(build.contains("maven-publish"),
                    example + " is a NON_PRODUCTION example, not an independent release (T46 Done-when)");
            assertFalse(build.contains(".."), example + ": no path escaping this boundary");
        }
    }

    @Test
    void librarySourcesImportNothingFromAnotherBoundary() throws IOException {
        try (var paths = Files.walk(LIBRARY_ROOT.resolve("src"))) {
            List<Path> offenders = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(StandaloneBoundaryTest::importsForeignPackage)
                    .collect(Collectors.toList());

            assertTrue(offenders.isEmpty(), "sources outside this boundary's package: " + offenders);
        }
    }

    private static boolean importsForeignPackage(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .filter(line -> line.startsWith("import "))
                    .anyMatch(line -> line.contains("com.example.payments")
                            || line.contains("com.example.platform.asyncredis"));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
