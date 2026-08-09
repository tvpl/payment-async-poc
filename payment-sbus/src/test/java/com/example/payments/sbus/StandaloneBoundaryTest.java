package com.example.payments.sbus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

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
    void ownsStandaloneBuildAndWrapper() {
        assertTrue(Files.isRegularFile(ROOT.resolve("settings.gradle")));
        assertTrue(Files.isRegularFile(ROOT.resolve("build.gradle")));
        assertTrue(Files.isRegularFile(ROOT.resolve("gradlew")));
        assertTrue(Files.isRegularFile(ROOT.resolve("gradle/wrapper/gradle-wrapper.jar")));
    }

    @Test
    void preservesAppliedMigrationBytes() throws IOException, NoSuchAlgorithmException {
        List<String> expected = Files.readAllLines(
                ROOT.resolve("src/test/resources/migration-checksums.sha256"));
        List<String> actual = expected.stream().map(line -> {
            String fileName = line.substring(line.indexOf("  ") + 2);
            Path migration = ROOT.resolve("src/main/resources/db/migration").resolve(fileName);
            try {
                return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(migration)))
                        + "  " + fileName;
            } catch (IOException | NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }).toList();

        assertEquals(expected, actual);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
