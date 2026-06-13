package com.opensmpsdeck.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.opensmpsdeck.library.InstrumentAssetKind;
import com.opensmpsdeck.library.InstrumentLibrary;
import com.opensmpsdeck.library.InstrumentLibraryEntry;
import com.opensmpsdeck.library.SourceReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.opensmpsdeck.io.HexUtil.bytesToHex;
import static com.opensmpsdeck.io.HexUtil.hexToBytes;

public final class InstrumentLibraryFile {

    private static final int VERSION = 1;
    private static final String LIBRARY_JSON = "library.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private InstrumentLibraryFile() {
    }

    public static void save(InstrumentLibrary library, Path root) throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("dac"));
        JsonObject json = toJson(library, root);
        Path tmp = root.resolve(LIBRARY_JSON + ".tmp");
        Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
        Path target = root.resolve(LIBRARY_JSON);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        library.clearDirty();
    }

    public static InstrumentLibrary load(Path root) throws IOException {
        Path libraryJson = root.resolve(LIBRARY_JSON);
        if (!Files.exists(libraryJson)) {
            return new InstrumentLibrary();
        }

        try {
            JsonObject json = JsonParser.parseString(Files.readString(libraryJson, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            validateVersion(json, libraryJson);
            JsonArray entries = json.getAsJsonArray("entries");
            if (entries == null) {
                throw new IOException("Instrument library file is missing entries array: " + libraryJson);
            }

            List<InstrumentLibraryEntry> loadedEntries = new ArrayList<>();
            for (JsonElement element : entries) {
                loadedEntries.add(fromEntryJson(element.getAsJsonObject(), root));
            }
            return InstrumentLibrary.fromEntries(loadedEntries);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
            throw new IOException("Invalid instrument library JSON: " + libraryJson, e);
        }
    }

    private static JsonObject toJson(InstrumentLibrary library, Path root) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("version", VERSION);
        JsonArray entries = new JsonArray();
        for (InstrumentLibraryEntry entry : library.entries()) {
            entries.add(toEntryJson(entry, root));
        }
        json.add("entries", entries);
        return json;
    }

    private static JsonObject toEntryJson(InstrumentLibraryEntry entry, Path libraryRoot) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("id", entry.id());
        json.addProperty("kind", entry.kind().name());
        json.addProperty("displayName", entry.displayName());
        json.addProperty("dedupeKey", entry.dedupeKey());
        json.addProperty("createdTimestamp", entry.createdTimestamp().toString());
        json.addProperty("updatedTimestamp", entry.updatedTimestamp().toString());
        if (entry.kind() == InstrumentAssetKind.DAC_SAMPLE) {
            String payload = dacPayloadPath(entry);
            Path payloadPath = libraryRoot.resolve(payload);
            Files.createDirectories(payloadPath.getParent());
            Files.write(payloadPath, entry.data());
            json.addProperty("payload", payload);
            json.addProperty("playbackRate", entry.playbackRate());
            json.addProperty("compressionLabel", entry.compressionLabel());
            json.addProperty("pan", entry.pan());
            json.addProperty("param1", entry.param1());
            json.addProperty("param2", entry.param2());
            json.addProperty("dacId", entry.dacId());
        } else {
            json.addProperty("data", bytesToHex(entry.data()));
        }
        json.add("sourceReferences", toSourcesJson(entry.sourceReferences()));
        return json;
    }

    private static InstrumentLibraryEntry fromEntryJson(JsonObject json, Path root) throws IOException {
        InstrumentAssetKind kind = InstrumentAssetKind.valueOf(requireString(json, "kind"));
        String displayName = requireString(json, "displayName");
        Instant created = Instant.parse(requireString(json, "createdTimestamp"));
        Instant updated = Instant.parse(requireString(json, "updatedTimestamp"));
        List<SourceReference> sources = fromSourcesJson(json.getAsJsonArray("sourceReferences"));
        SourceReference firstSource = sources.isEmpty() ? null : sources.getFirst();

        byte[] data;
        InstrumentLibraryEntry entry;
        if (kind == InstrumentAssetKind.DAC_SAMPLE) {
            data = Files.readAllBytes(resolveDacPayload(root, requireString(json, "payload")));
            entry = InstrumentLibraryEntry.dacSample(
                    displayName,
                    data,
                    requireInt(json, "playbackRate"),
                    optionalString(json, "compressionLabel"),
                    optionalString(json, "pan"),
                    optionalString(json, "param1"),
                    optionalString(json, "param2"),
                    optionalString(json, "dacId"),
                    firstSource);
        } else {
            data = hexToBytes(requireString(json, "data"));
            entry = switch (kind) {
                case FM_VOICE -> InstrumentLibraryEntry.fmVoice(displayName, data, firstSource);
                case PSG_ENVELOPE -> InstrumentLibraryEntry.psgEnvelope(displayName, data, firstSource);
                case MOD_ENVELOPE -> InstrumentLibraryEntry.modEnvelope(displayName, data, firstSource);
                case DAC_SAMPLE -> throw new IOException("Unexpected DAC entry without payload");
            };
        }

        if (sources.size() > 1) {
            entry = entry.withMergedSources(sources.subList(1, sources.size()), updated);
        }
        return entry.withTimestamps(created, updated);
    }

    private static void validateVersion(JsonObject json, Path libraryJson) throws IOException {
        JsonElement element = json.get("version");
        if (element == null || element.isJsonNull()) {
            throw new IOException("Instrument library file is missing required version field: " + libraryJson);
        }
        int version = element.getAsInt();
        if (version > VERSION) {
            throw new IOException(
                    "Instrument library file version " + version + " is newer than supported version " + VERSION);
        }
    }

    private static Path resolveDacPayload(Path root, String payload) throws IOException {
        Path payloadPath = Path.of(payload);
        if (payloadPath.isAbsolute() || containsParentTraversal(payloadPath)) {
            throw new IOException("DAC payload path must stay under the library dac directory: " + payload);
        }

        Path libraryRoot = root.toAbsolutePath().normalize();
        Path dacRoot = libraryRoot.resolve("dac").normalize();
        Path resolved = libraryRoot.resolve(payloadPath).normalize();
        if (!resolved.startsWith(dacRoot)) {
            throw new IOException("DAC payload path must stay under the library dac directory: " + payload);
        }
        return resolved;
    }

    private static boolean containsParentTraversal(Path path) {
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray toSourcesJson(List<SourceReference> sources) {
        JsonArray array = new JsonArray();
        for (SourceReference source : sources) {
            JsonObject json = new JsonObject();
            json.addProperty("scanRoot", source.scanRoot());
            json.addProperty("driverFamily", source.driverFamily());
            json.addProperty("gameName", source.gameName());
            json.addProperty("variantPath", source.variantPath());
            json.addProperty("configExtension", source.configExtension());
            json.addProperty("sourceSongFile", source.sourceSongFile());
            json.addProperty("sourceCompanionFile", source.sourceCompanionFile());
            json.addProperty("originalIndexOrId", source.originalIndexOrId());
            json.addProperty("driverSummary", source.driverSummary());
            array.add(json);
        }
        return array;
    }

    private static List<SourceReference> fromSourcesJson(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<SourceReference> sources = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            sources.add(new SourceReference(
                    optionalString(json, "scanRoot"),
                    optionalString(json, "driverFamily"),
                    optionalString(json, "gameName"),
                    optionalString(json, "variantPath"),
                    optionalString(json, "configExtension"),
                    optionalString(json, "sourceSongFile"),
                    optionalString(json, "sourceCompanionFile"),
                    optionalString(json, "originalIndexOrId"),
                    optionalString(json, "driverSummary")));
        }
        return sources;
    }

    private static String dacPayloadPath(InstrumentLibraryEntry entry) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(entry.data());
            return "dac/" + bytesToLowerHex(digest.digest()) + ".pcm";
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 digest is not available", e);
        }
    }

    private static String bytesToLowerHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte b : data) {
            builder.append(String.format("%02x", b & 0xFF));
        }
        return builder.toString();
    }

    private static String requireString(JsonObject json, String field) throws IOException {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IOException("Instrument library entry is missing required field: " + field);
        }
        return element.getAsString();
    }

    private static int requireInt(JsonObject json, String field) throws IOException {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            throw new IOException("Instrument library entry is missing required field: " + field);
        }
        return element.getAsInt();
    }

    private static String optionalString(JsonObject json, String field) {
        JsonElement element = json.get(field);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}
