package com.example.payments.contracts.compatibility;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FullTransitiveCompatibility {

    private FullTransitiveCompatibility() {
    }

    static List<String> verify(Path candidateDirectory, Path historyDirectory) throws IOException {
        Map<String, Schema> candidate = parseDirectory(candidateDirectory);
        List<String> errors = new ArrayList<>();

        try (var versions = Files.list(historyDirectory)) {
            for (Path version : versions.filter(Files::isDirectory).sorted().toList()) {
                Map<String, Schema> previous = parseDirectory(version);
                for (Map.Entry<String, Schema> entry : previous.entrySet()) {
                    Schema current = candidate.get(entry.getKey());
                    if (current == null) {
                        errors.add(version.getFileName() + ": missing schema " + entry.getKey());
                        continue;
                    }
                    addCompatibilityError(errors, version, entry.getKey(), "backward", current, entry.getValue());
                    addCompatibilityError(errors, version, entry.getKey(), "forward", entry.getValue(), current);
                }
            }
        }
        return errors;
    }

    static boolean isFullCompatible(Schema previous, Schema candidate) {
        return compatibility(candidate, previous).getType()
                        == SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE
                && compatibility(previous, candidate).getType()
                        == SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE;
    }

    static boolean permitsBreakingChange(ReleaseIdentity previous, ReleaseIdentity candidate) {
        return candidate.major() > previous.major()
                && !candidate.artifactId().equals(previous.artifactId())
                && !candidate.topic().equals(previous.topic())
                && candidate.coexistsWithPrevious();
    }

    private static void addCompatibilityError(
            List<String> errors,
            Path version,
            String schemaName,
            String direction,
            Schema reader,
            Schema writer) {
        var result = compatibility(reader, writer);
        if (result.getType() != SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE) {
            errors.add(version.getFileName() + ": " + schemaName + " is not " + direction + " compatible: "
                    + result.getDescription());
        }
    }

    private static SchemaCompatibility.SchemaPairCompatibility compatibility(Schema reader, Schema writer) {
        return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer);
    }

    private static Map<String, Schema> parseDirectory(Path directory) throws IOException {
        List<Path> pending;
        try (var files = Files.list(directory)) {
            pending = new ArrayList<>(files
                    .filter(path -> path.getFileName().toString().endsWith(".avsc"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList());
        }

        Map<String, Schema> parsed = new LinkedHashMap<>();
        Map<String, Schema> knownTypes = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            int previousSize = pending.size();
            var iterator = pending.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                try {
                    var parser = new Schema.Parser();
                    parser.addTypes(knownTypes);
                    Schema schema = parser.parse(path.toFile());
                    parsed.put(path.getFileName().toString(), schema);
                    knownTypes.put(schema.getFullName(), schema);
                    iterator.remove();
                } catch (SchemaParseException unresolvedReference) {
                    // Retry after schemas that define referenced named types have been parsed.
                }
            }
            if (pending.size() == previousSize) {
                throw new SchemaParseException("Unresolved or invalid schemas: " + pending);
            }
        }
        return parsed;
    }

    record ReleaseIdentity(int major, String artifactId, String topic, boolean coexistsWithPrevious) {
    }
}
