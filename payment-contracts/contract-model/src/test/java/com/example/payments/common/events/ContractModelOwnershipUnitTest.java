package com.example.payments.common.events;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContractModelOwnershipUnitTest {

    @Test
    void containsOnlyFrameworkAgnosticContractCode() throws IOException {
        Path boundaryRoot = Path.of("..").toAbsolutePath().normalize();
        Path sourceRoot = Path.of("src/main/java/com/example/payments/common");
        List<String> forbiddenRuntimeMarkers = List.of(
                "/ratelimit/",
                "/kafka/",
                "/mapping/",
                "Controller.java",
                "Persistence.java",
                "RateLimiter.java"
        );
        List<String> forbiddenFrameworkMarkers = List.of(
                "micronaut",
                "jakarta",
                "@Serdeable",
                "@Singleton",
                "@Inject",
                "@Value",
                "@PreDestroy"
        );
        List<String> violations = new ArrayList<>();

        try (var sources = Files.walk(sourceRoot)) {
            sources
                    .filter(Files::isRegularFile)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> forbiddenRuntimeMarkers.stream().anyMatch(path::contains))
                    .forEach(violations::add);
        }

        try (var boundaryFiles = Files.walk(boundaryRoot)) {
            boundaryFiles
                    .filter(Files::isRegularFile)
                    .filter(ContractModelOwnershipUnitTest::isBuildOrProductionSource)
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path);
                            forbiddenFrameworkMarkers.stream()
                                    .filter(content::contains)
                                    .map(marker -> boundaryRoot.relativize(path) + " -> " + marker)
                                    .forEach(violations::add);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Cannot inspect " + path, exception);
                        }
                    });
        }

        assertEquals(List.of(), violations);
    }

    private static boolean isBuildOrProductionSource(Path path) {
        String normalized = path.toString().replace('\\', '/');
        String fileName = path.getFileName().toString();
        if (normalized.contains("/.gradle/") || normalized.contains("/build/")) {
            return false;
        }
        return fileName.endsWith(".gradle")
                || fileName.endsWith(".gradle.kts")
                || fileName.equals("gradle.properties")
                || fileName.equals("libs.versions.toml")
                || (normalized.contains("/src/main/java/") && normalized.endsWith(".java"));
    }
}
