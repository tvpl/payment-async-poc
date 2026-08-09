package com.example.payments.common.events;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContractModelOwnershipUnitTest {

    @Test
    void containsNoApplicationRuntimeConcern() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/example/payments/common");
        List<String> forbiddenMarkers = List.of(
                "/ratelimit/",
                "/kafka/",
                "/mapping/",
                "Controller.java",
                "Persistence.java",
                "RateLimiter.java"
        );

        try (var sources = Files.walk(sourceRoot)) {
            List<String> violations = sources
                    .filter(Files::isRegularFile)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> forbiddenMarkers.stream().anyMatch(path::contains))
                    .toList();

            assertEquals(List.of(), violations);
        }
    }
}
