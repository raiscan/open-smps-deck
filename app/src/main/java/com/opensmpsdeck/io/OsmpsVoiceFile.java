package com.opensmpsdeck.io;

import com.google.gson.*;
import com.opensmpsdeck.model.FmVoice;

import static com.opensmpsdeck.io.HexUtil.bytesToHex;
import static com.opensmpsdeck.io.HexUtil.hexToBytes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Reads and writes single FM voice preset files ({@code .osmpsvoice}) as JSON.
 *
 * <p>Format:
 * <pre>{@code
 * {
 *   "version": 1,
 *   "name": "BrassLead",
 *   "data": "3C 71 22 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
 * }
 * }</pre>
 *
 * <p>Hex data is encoded as space-separated uppercase hex bytes (e.g. "3C 71 22").
 */
public final class OsmpsVoiceFile {

    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private OsmpsVoiceFile() {
        // Utility class
    }

    /**
     * Saves a single FM voice preset to an {@code .osmpsvoice} file as pretty-printed JSON.
     *
     * @param voice the FM voice to save
     * @param file  the destination file
     * @throws IOException if writing fails
     */
    public static void save(FmVoice voice, File file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("name", voice.getName());
        root.addProperty("data", bytesToHex(voice.getData()));

        Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    /**
     * Loads a single FM voice preset from an {@code .osmpsvoice} file.
     *
     * @param file the voice preset file to load
     * @return the loaded FM voice
     * @throws IOException if reading or parsing fails, or if the file version is unsupported
     */
    public static FmVoice load(File file) throws IOException {
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        JsonElement versionElem = root.get("version");
        if (versionElem == null) {
            throw new IOException("Voice preset file is missing required 'version' field: " + file.getName());
        }
        int fileVersion = versionElem.getAsInt();
        if (fileVersion > VERSION) {
            throw new IOException(
                    "Voice preset file version " + fileVersion + " is newer than supported version " + VERSION
                    + ". Please update OpenSMPSDeck.");
        }

        if (root.get("name") == null) {
            throw new IOException("Voice preset file is missing required 'name' field: " + file.getName());
        }
        String name = root.get("name").getAsString();

        if (root.get("data") == null) {
            throw new IOException("Voice preset file is missing required 'data' field: " + file.getName());
        }
        byte[] data = hexToBytes(root.get("data").getAsString());

        if (data.length != FmVoice.VOICE_SIZE) {
            throw new IOException(
                    "Voice preset data must be exactly " + FmVoice.VOICE_SIZE + " bytes, got " + data.length
                    + ": " + file.getName());
        }

        return new FmVoice(name, data);
    }
}
