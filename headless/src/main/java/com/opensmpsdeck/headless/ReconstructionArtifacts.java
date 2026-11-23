package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class ReconstructionArtifacts {
    private static final long MAX_ARTIFACT_BYTES = 32L * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder()
            .disableHtmlEscaping().setPrettyPrinting().create();

    private ReconstructionArtifacts() {}

    static <T> T read(Path file, Class<T> type) throws IOException {
        return ReconstructionLiveEvidence.artifact(file, type, () -> decode(file, type, readTree(file)));
    }

    static JsonElement readTree(Path file) throws IOException {
        if (Files.size(file) > MAX_ARTIFACT_BYTES)
            throw new IllegalArgumentException(file + ": artifact exceeds 32 MiB");
        try (var reader = Files.newBufferedReader(file)) {
            var stream = new JsonReader(reader);
            var tree = JSON.getAdapter(JsonElement.class).read(stream);
            require(stream.peek() == JsonToken.END_DOCUMENT,
                    file.toString(), "unexpected content after JSON value");
            return tree;
        } catch (JsonParseException | MalformedJsonException | EOFException malformed) {
            throw new IllegalArgumentException(file + ": " + malformed.getMessage(), malformed);
        }
    }

    static <T> T decode(Path file, Class<T> type, JsonElement tree) {
        shape(file.getFileName().toString(), type, tree);
        var version = tree.getAsJsonObject().get("version");
        require(version.getAsInt() == ReconstructionStages.VERSION, file + ".version", "unsupported artifact version");
        return JSON.fromJson(tree, type);
    }

    static Map<String, String> noteHashes(List<ReconstructionStages.Note> notes) {
        var hashes = new java.util.TreeMap<String, String>();
        for (var note : notes) hashes.put(note.id(), fingerprint(note));
        return hashes;
    }

    static String fingerprint(Object value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var writer = new java.io.OutputStreamWriter(
                    new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                JSON.toJson(value, writer);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void write(Path file, Object value) throws IOException {
        Path pending = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(pending)) {
                JSON.toJson(value, writer);
                writer.write('\n');
            }
            if (Files.size(pending) > MAX_ARTIFACT_BYTES)
                throw new IllegalArgumentException(file + ": artifact exceeds 32 MiB");
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    static void shape(String at, Type type, JsonElement value) {
        require(value != null && !value.isJsonNull(), at, "required");
        if (type instanceof ParameterizedType generic) {
            Type[] args = generic.getActualTypeArguments();
            if (generic.getRawType() == List.class) {
                require(value.isJsonArray(), at, "expected an array");
                for (int i = 0; i < value.getAsJsonArray().size(); i++)
                    shape(at + "[" + i + "]", args[0], value.getAsJsonArray().get(i));
            } else {
                require(generic.getRawType() == Map.class && value.isJsonObject(), at, "expected an object");
                for (var field : value.getAsJsonObject().entrySet()) {
                    if (args[0] == Integer.class) {
                        try {
                            require(Integer.parseInt(field.getKey()) >= 0, at, "expected nonnegative integer keys");
                        } catch (NumberFormatException invalid) {
                            throw new IllegalArgumentException(at + ": expected nonnegative integer keys", invalid);
                        }
                    }
                    shape(at + "." + field.getKey(), args[1], field.getValue());
                }
            }
        } else if (type == JsonObject.class) {
            require(value.isJsonObject(), at, "expected an object");
        } else if (type == ReconstructionLanePolicies.Configuration.class) {
            // Its adapter validates the explicit-rule array or named preset object.
            return;
        } else if (type instanceof Class<?> clazz && clazz.isRecord()) {
            require(value.isJsonObject(), at, "expected an object");
            var object = value.getAsJsonObject();
            var keys = new HashSet<String>();
            for (var field : clazz.getRecordComponents()) {
                String name = field.getName();
                keys.add(name);
                JsonElement member = object.get(name);
                // Embedded project samples use the project format's optional provenance fields.
                if (member == null && optionalProjectField(clazz, name)) continue;
                if (field.isAnnotationPresent(ReconstructionStages.Nullable.class)
                        && (member == null || member.isJsonNull())) continue;
                shape(at + "." + name, field.getGenericType(), member);
            }
            for (String name : object.keySet()) require(keys.contains(name), at + "." + name, "unknown field");
        } else {
            require(value.isJsonPrimitive(), at, "expected a scalar");
            var scalar = value.getAsJsonPrimitive();
            if (type == String.class) {
                require(scalar.isString(), at, "expected a string");
            } else if (type == boolean.class || type == Boolean.class) {
                require(scalar.isBoolean(), at, "expected a boolean");
            } else if (type instanceof Class<?> clazz && clazz.isEnum()) {
                require(scalar.isString() && java.util.Arrays.stream(clazz.getEnumConstants())
                        .anyMatch(choice -> ((Enum<?>) choice).name().equals(scalar.getAsString())), at, "unknown value");
            } else {
                require(scalar.isNumber() && Double.isFinite(scalar.getAsDouble()), at, "expected a finite number");
                try {
                    if (type == int.class || type == Integer.class) scalar.getAsBigDecimal().intValueExact();
                    if (type == long.class) scalar.getAsBigDecimal().longValueExact();
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException(at + ": expected an integer in range", e);
                }
            }
        }
    }

    private static boolean optionalProjectField(Class<?> type, String name) {
        if (type == com.opensmpsdeck.project.DacSample.class) return name.equals("origin");
        if (type == com.opensmpsdeck.project.AssetOrigin.class) return !name.equals("type");
        if (type == com.opensmpsdeck.project.StockRef.class)
            return java.util.Set.of("songId", "slot", "index", "note").contains(name);
        return false;
    }

    static void require(boolean condition, String at, String message) {
        if (!condition) throw new IllegalArgumentException(at + ": " + message);
    }
}
