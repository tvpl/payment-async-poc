package com.example.payments.common.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fails the build the moment a payload model declares a field whose name matches the sensitive
 * data denylist (SEC-07). The policy and its evolution are recorded in
 * {@code docs/adr/0002-sensitive-field-denylist.md}.
 */
class SensitiveFieldGuardUnitTest {

    /** Substring match, case-insensitive: catches variants like cardNumber, panMasked, authToken. */
    private static final List<String> DENYLIST =
            List.of("pan", "card", "cvv", "cvc", "password", "secret", "token");

    private static final Path MODEL_SOURCE_ROOT = Path.of("src/main/java/com/example/payments/common/model");

    @Test
    void payloadModelsCarryNoFieldNameFromTheSensitiveDenylist() throws IOException, ClassNotFoundException {
        List<String> violations = new ArrayList<>();
        for (Class<?> modelClass : modelClasses()) {
            for (Field field : modelClass.getDeclaredFields()) {
                String lowerName = field.getName().toLowerCase(Locale.ROOT);
                for (String denied : DENYLIST) {
                    if (lowerName.contains(denied)) {
                        violations.add(modelClass.getSimpleName() + "." + field.getName()
                                + " matches denylist term '" + denied + "'");
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    /** Reflective scan of every model class actually shipped, so a new model is covered for free. */
    private static List<Class<?>> modelClasses() throws IOException, ClassNotFoundException {
        List<Class<?>> classes = new ArrayList<>();
        try (var files = Files.list(MODEL_SOURCE_ROOT)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList()) {
                String simpleName = path.getFileName().toString().replace(".java", "");
                classes.add(Class.forName("com.example.payments.common.model." + simpleName));
            }
        }
        return classes;
    }
}
