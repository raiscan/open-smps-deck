package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.StockRef;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record PackageManifest(int schemaVersion, String profile, String driver, Metadata.PackageInfo packageInfo,
                       Generator generator, Provenance provenance, Requirements requires, JsonObject driverOptions,
                       Bank bank, List<Asset> assets, List<EnvelopeBinding> envelopeBindings,
                       List<DacBinding> dacBindings, @Nullable GlobalVoices globalVoices, @Nullable Source source) {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson OPTIONAL_FIELDS = new GsonBuilder().disableHtmlEscaping().create();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    @interface Nullable {}

    record Generator(String tool, String version, String openggfAudio) {}
    record Provenance(boolean containsRomDerivedData, boolean containsRetailSongData,
                      @Nullable Retail derivedFromRetailSong, List<String> warnings) {}
    record Retail(String game, int songId, String bankSha256) {}
    record Requirements(boolean stockAssets, List<String> games) {}
    record Bank(String form, String path, String sha256, String assembledSha256, int base, int headerOffset,
                int localVoiceCount, int previewFrames, List<Integer> relocations, boolean relocationsComplete,
                List<Fill> fills) {}
    record Fill(int offset, int length, String asset) {}
    record EnvelopeBinding(String asset, int slot) {}
    record DacBinding(int note, String asset, int rate) {}
    record GlobalVoices(String path, String sha256, List<String> voices) {}
    record Source(String path, String sha256, boolean redacted) {}

    enum Kind {
        FM_VOICE("fm"), PSG_VOLUME("psg"), MODULATION("mod"), DAC_SAMPLE("dac");
        final String prefix;
        Kind(String prefix) { this.prefix = prefix; }

        String path(int ordinal) {
            return switch (this) {
                case FM_VOICE -> "voices/global.bin";
                case DAC_SAMPLE -> String.format(java.util.Locale.ROOT, "dac/asset-%03d.pcm", ordinal);
                default -> String.format(java.util.Locale.ROOT, "%s/asset-%03d.bin", prefix, ordinal + 1);
            };
        }

        void validateLength(String key, int length) {
            int min = this == FM_VOICE ? 25 : 1;
            int max = this == FM_VOICE ? 25 : this == DAC_SAMPLE ? 65535 : 256;
            range(length, min, max, key + " length");
        }
    }

    record Origin(String type, String license, String attribution) {
        static Origin of(AssetOrigin origin) {
            return origin == null ? new Origin("original", null, null)
                    : new Origin(origin.type(), origin.license(), origin.attribution());
        }
    }

    record Asset(String key, Kind kind, String id, String name, Origin origin,
                 StockRef ref, List<AssetOrigin.Transform> transforms, String resultSha256,
                 String path, String sha256, Integer bankOffset) {}

    JsonObject json() {
        // The record component is named packageInfo because package is a Java keyword.
        var model = JSON.toJsonTree(this).getAsJsonObject();
        var result = new JsonObject();
        for (var entry : model.entrySet()) {
            if (entry.getKey().equals("assets")) {
                result.add("assets", OPTIONAL_FIELDS.toJsonTree(assets));
            } else {
                result.add(entry.getKey().equals("packageInfo") ? "package" : entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    byte[] encode() { return (JSON.toJson(json()) + "\n").getBytes(StandardCharsets.UTF_8); }

    static PackageManifest decode(byte[] data) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
        var root = object(ProjectFormat.parseIntegerJson(text), "manifest");
        require(root.has("schemaVersion") && root.get("schemaVersion").equals(new com.google.gson.JsonPrimitive(4)),
                "Expected package schema 4");
        require(!root.has("packageInfo") && root.has("package"), "manifest.package is required");
        root.add("packageInfo", root.remove("package"));
        shape(PackageManifest.class, root, "manifest");
        return JSON.fromJson(root, PackageManifest.class);
    }

    private static void shape(Type type, JsonElement value, String at) {
        require(value != null && !value.isJsonNull(), at + " is required");
        if (type instanceof ParameterizedType list) {
            require(value.isJsonArray(), at + " must be an array");
            int i = 0;
            for (var item : value.getAsJsonArray())
                shape(list.getActualTypeArguments()[0], item, at + "[" + i++ + "]");
            return;
        }
        Class<?> clazz = (Class<?>) type;
        if (clazz == JsonObject.class) {
            object(value, at);
        } else if (clazz == Asset.class) {
            assetShape(value, at);
        } else if (clazz.isRecord()) {
            var object = object(value, at);
            var keys = new HashSet<String>();
            for (var field : clazz.getRecordComponents()) {
                String name = field.getName();
                keys.add(name);
                require(object.has(name), at + "." + name + " is required");
                var member = object.get(name);
                if (member.isJsonNull() && field.isAnnotationPresent(Nullable.class)) continue;
                shape(field.getGenericType(), member, at + "." + name);
            }
            require(object.keySet().equals(keys), at + " contains unknown fields");
        } else {
            require(value.isJsonPrimitive(), at + " must be a scalar");
            var scalar = value.getAsJsonPrimitive();
            require(clazz == String.class ? scalar.isString()
                    : clazz == boolean.class ? scalar.isBoolean() : scalar.isNumber(), at + " has the wrong scalar type");
        }
    }

    private static void assetShape(JsonElement value, String at) {
        var asset = object(value, at);
        var keys = new HashSet<>(Set.of("key", "kind", "id", "name", "origin"));
        for (String key : List.of("key", "kind", "id", "name")) shape(String.class, asset.get(key), at + "." + key);
        var origin = object(asset.get("origin"), at + ".origin");
        shape(String.class, origin.get("type"), at + ".origin.type");
        var originKeys = new HashSet<>(Set.of("type"));
        if ("third-party".equals(origin.get("type").getAsString())) {
            for (String key : List.of("license", "attribution")) {
                shape(String.class, origin.get(key), at + ".origin." + key);
                originKeys.add(key);
            }
        }
        require(origin.keySet().equals(originKeys), at + ".origin contains unknown fields");
        if (asset.has("ref")) {
            ProjectFormat.stockRef(asset.get("ref"));
            keys.addAll(Set.of("ref", "transforms"));
            require(asset.has("transforms") && asset.get("transforms").isJsonArray(), at + ".transforms must be an array");
            var transforms = asset.getAsJsonArray("transforms");
            transforms.forEach(ProjectFormat::transform);
            if (!transforms.isEmpty()) {
                keys.add("resultSha256");
                shape(String.class, asset.get("resultSha256"), at + ".resultSha256");
            }
        } else if (asset.has("bankOffset")) {
            keys.add("bankOffset");
            shape(int.class, asset.get("bankOffset"), at + ".bankOffset");
        } else {
            keys.addAll(Set.of("path", "sha256"));
            shape(String.class, asset.get("path"), at + ".path");
            shape(String.class, asset.get("sha256"), at + ".sha256");
        }
        require(asset.keySet().equals(keys), at + " has unknown or conflicting payload fields");
    }

    static JsonObject object(JsonElement value, String name) {
        require(value != null && value.isJsonObject(), name + " must be an object");
        return value.getAsJsonObject();
    }

    static void require(boolean result, String error) {
        if (!result) throw new IllegalArgumentException(error);
    }

    static void range(int value, int min, int max, String name) {
        require(min <= value && value <= max, name + " must be in " + min + ".." + max);
    }

    static void hash(String value, String name) {
        require(value != null && value.matches("[0-9a-f]{64}"), name + " must be a lowercase SHA-256");
    }

    static void nonblank(String value, String name) {
        require(value != null && !value.isBlank(), name + " must be nonblank");
    }
}
