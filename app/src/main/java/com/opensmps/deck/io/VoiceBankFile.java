package com.opensmps.deck.io;

import com.google.gson.*;
import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.PsgEnvelope;

import static com.opensmps.deck.io.HexUtil.bytesToHex;
import static com.opensmps.deck.io.HexUtil.hexToBytes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes voice bank files ({@code .ovm}) as JSON.
 *
 * <p>Format:
 * <pre>{@code
 * {
 *   "version": 1,
 *   "name": "...",
 *   "voices": [{"name": "...", "data": "hex"}],
 *   "psgEnvelopes": [{"name": "...", "data": "hex"}]
 * }
 * }</pre>
 *
 * <p>Hex data is encoded as space-separated uppercase hex bytes (e.g. "3A 07 1F").
 */
public class VoiceBankFile {

    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private VoiceBankFile() {
        // Utility class
    }

    /**
     * Result of loading a voice bank file.
     */
    public record LoadResult(String name, List<FmVoice> voices, List<PsgEnvelope> psgEnvelopes) {}

    /**
     * Saves a voice bank to an {@code .ovm} file as pretty-printed JSON.
     *
     * @param name         the bank name
     * @param voices       FM voices to include
     * @param psgEnvelopes PSG envelopes to include
     * @param file         the destination file
     * @throws IOException if writing fails
     */
    public static void save(String name, List<FmVoice> voices, List<PsgEnvelope> psgEnvelopes, File file)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("name", name);

        JsonArray voiceArray = new JsonArray();
        for (FmVoice voice : voices) {
            JsonObject v = new JsonObject();
            v.addProperty("name", voice.getName());
            v.addProperty("data", bytesToHex(voice.getData()));
            voiceArray.add(v);
        }
        root.add("voices", voiceArray);

        JsonArray envArray = new JsonArray();
        for (PsgEnvelope env : psgEnvelopes) {
            JsonObject e = new JsonObject();
            e.addProperty("name", env.getName());
            e.addProperty("data", bytesToHex(env.getData()));
            envArray.add(e);
        }
        root.add("psgEnvelopes", envArray);

        Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    /**
     * Loads a voice bank from an {@code .ovm} file.
     *
     * @param file the voice bank file to load
     * @return the loaded result containing name, voices, and PSG envelopes
     * @throws IOException if reading or parsing fails, or if the file version is unsupported
     */
    public static LoadResult load(File file) throws IOException {
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        JsonElement versionElem = root.get("version");
        if (versionElem == null) {
            throw new IOException("Voice bank file is missing required 'version' field: " + file.getName());
        }
        int fileVersion = versionElem.getAsInt();
        if (fileVersion > VERSION) {
            throw new IOException(
                    "Voice bank file version " + fileVersion + " is newer than supported version " + VERSION
                    + ". Please update OpenSMPS Deck.");
        }

        requireField(root, "name", file);
        String name = root.get("name").getAsString();

        JsonArray voicesArray = root.getAsJsonArray("voices");
        if (voicesArray == null) {
            throw new IOException("Voice bank file is missing required 'voices' array: " + file.getName());
        }
        List<FmVoice> voices = new ArrayList<>();
        for (JsonElement elem : voicesArray) {
            JsonObject v = elem.getAsJsonObject();
            requireField(v, "name", "voices entry", file);
            requireField(v, "data", "voices entry", file);
            voices.add(new FmVoice(
                    v.get("name").getAsString(),
                    hexToBytes(v.get("data").getAsString())
            ));
        }

        List<PsgEnvelope> psgEnvelopes = new ArrayList<>();
        JsonArray psgArray = root.getAsJsonArray("psgEnvelopes");
        if (psgArray != null) {
            for (JsonElement elem : psgArray) {
                JsonObject e = elem.getAsJsonObject();
                requireField(e, "name", "psgEnvelopes entry", file);
                requireField(e, "data", "psgEnvelopes entry", file);
                psgEnvelopes.add(new PsgEnvelope(
                        e.get("name").getAsString(),
                        hexToBytes(e.get("data").getAsString())
                ));
            }
        }

        return new LoadResult(name, voices, psgEnvelopes);
    }

    private static void requireField(JsonObject obj, String field, File file) throws IOException {
        if (obj.get(field) == null) {
            throw new IOException("Voice bank file is missing required '" + field + "' field: " + file.getName());
        }
    }

    private static void requireField(JsonObject obj, String field, String context, File file) throws IOException {
        if (obj.get(field) == null) {
            throw new IOException("Missing required '" + field + "' in " + context + ": " + file.getName());
        }
    }
}
