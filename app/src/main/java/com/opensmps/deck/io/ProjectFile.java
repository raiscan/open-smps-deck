package com.opensmps.deck.io;

import com.google.gson.*;
import com.opensmps.deck.model.*;

import static com.opensmps.deck.io.HexUtil.bytesToHex;
import static com.opensmps.deck.io.HexUtil.hexToBytes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Saves and loads OpenSMPS Deck project files ({@code .osmpsd}).
 *
 * <p>Projects are stored as JSON with hex-encoded binary data for track
 * bytes and voice/envelope data. The format is versioned for future
 * compatibility.
 */
public class ProjectFile {

    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Saves a song to a {@code .osmpsd} project file as pretty-printed JSON.
     *
     * @param song the song to save
     * @param file the destination file
     * @throws IOException if writing fails
     */
    public static void save(Song song, File file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("name", song.getName());
        root.addProperty("smpsMode", song.getSmpsMode().name());
        root.addProperty("tempo", song.getTempo());
        root.addProperty("dividingTiming", song.getDividingTiming());
        root.addProperty("loopPoint", song.getLoopPoint());

        // Voice bank
        JsonArray voices = new JsonArray();
        for (FmVoice voice : song.getVoiceBank()) {
            JsonObject v = new JsonObject();
            v.addProperty("name", voice.getName());
            v.addProperty("data", bytesToHex(voice.getData()));
            voices.add(v);
        }
        root.add("voiceBank", voices);

        // PSG envelopes
        JsonArray envelopes = new JsonArray();
        for (PsgEnvelope env : song.getPsgEnvelopes()) {
            JsonObject e = new JsonObject();
            e.addProperty("name", env.getName());
            e.addProperty("data", bytesToHex(env.getData()));
            envelopes.add(e);
        }
        root.add("psgEnvelopes", envelopes);

        // Order list
        JsonArray orders = new JsonArray();
        for (int[] row : song.getOrderList()) {
            JsonArray orderRow = new JsonArray();
            for (int val : row) {
                orderRow.add(val);
            }
            orders.add(orderRow);
        }
        root.add("orderList", orders);

        // Patterns
        JsonArray patterns = new JsonArray();
        for (Pattern pat : song.getPatterns()) {
            JsonObject p = new JsonObject();
            p.addProperty("id", pat.getId());
            p.addProperty("rows", pat.getRows());
            JsonObject tracks = new JsonObject();
            for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                byte[] data = pat.getTrackDataDirect(ch);
                if (data != null && data.length > 0) {
                    tracks.addProperty(String.valueOf(ch), bytesToHex(data));
                }
            }
            p.add("tracks", tracks);
            patterns.add(p);
        }
        root.add("patterns", patterns);

        // DAC samples
        JsonArray dacSamples = new JsonArray();
        for (DacSample sample : song.getDacSamples()) {
            JsonObject s = new JsonObject();
            s.addProperty("name", sample.getName());
            s.addProperty("rate", sample.getRate());
            s.addProperty("data", bytesToHex(sample.getData()));
            dacSamples.add(s);
        }
        root.add("dacSamples", dacSamples);

        Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    /**
     * Loads a song from a {@code .osmpsd} project file.
     *
     * @param file the project file to load
     * @return the loaded song
     * @throws IOException if reading or parsing fails
     */
    public static Song load(File file) throws IOException {
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        JsonElement versionElem = root.get("version");
        if (versionElem == null) {
            throw new IOException("Project file is missing required 'version' field: " + file.getName());
        }
        int fileVersion = versionElem.getAsInt();
        if (fileVersion > VERSION) {
            throw new IOException(
                "Project file version " + fileVersion + " is newer than supported version " + VERSION
                + ". Please update OpenSMPS Deck.");
        }

        Song song = new Song();
        // Clear defaults added by constructor
        song.getPatterns().clear();
        song.getOrderList().clear();

        requireField(root, "name", file);
        requireField(root, "smpsMode", file);
        requireField(root, "tempo", file);
        requireField(root, "dividingTiming", file);
        requireField(root, "loopPoint", file);

        song.setName(root.get("name").getAsString());
        song.setSmpsMode(SmpsMode.valueOf(root.get("smpsMode").getAsString()));
        song.setTempo(root.get("tempo").getAsInt());
        song.setDividingTiming(root.get("dividingTiming").getAsInt());
        song.setLoopPoint(root.get("loopPoint").getAsInt());

        // Voice bank
        JsonArray voiceBankArray = root.getAsJsonArray("voiceBank");
        if (voiceBankArray == null) {
            throw new IOException("Project file is missing required 'voiceBank' array: " + file.getName());
        }
        for (JsonElement elem : voiceBankArray) {
            JsonObject v = elem.getAsJsonObject();
            requireField(v, "name", "voiceBank entry", file);
            requireField(v, "data", "voiceBank entry", file);
            song.getVoiceBank().add(new FmVoice(
                    v.get("name").getAsString(),
                    hexToBytes(v.get("data").getAsString())
            ));
        }

        // PSG envelopes
        JsonArray psgEnvelopesArray = root.getAsJsonArray("psgEnvelopes");
        if (psgEnvelopesArray == null) {
            throw new IOException("Project file is missing required 'psgEnvelopes' array: " + file.getName());
        }
        for (JsonElement elem : psgEnvelopesArray) {
            JsonObject e = elem.getAsJsonObject();
            requireField(e, "name", "psgEnvelopes entry", file);
            requireField(e, "data", "psgEnvelopes entry", file);
            song.getPsgEnvelopes().add(new PsgEnvelope(
                    e.get("name").getAsString(),
                    hexToBytes(e.get("data").getAsString())
            ));
        }

        // Order list
        JsonArray orderListArray = root.getAsJsonArray("orderList");
        if (orderListArray == null) {
            throw new IOException("Project file is missing required 'orderList' array: " + file.getName());
        }
        for (JsonElement elem : orderListArray) {
            JsonArray row = elem.getAsJsonArray();
            int[] orderRow = new int[Pattern.CHANNEL_COUNT];
            for (int i = 0; i < Math.min(row.size(), orderRow.length); i++) {
                orderRow[i] = row.get(i).getAsInt();
            }
            song.getOrderList().add(orderRow);
        }

        // Patterns
        JsonArray patternsArray = root.getAsJsonArray("patterns");
        if (patternsArray == null) {
            throw new IOException("Project file is missing required 'patterns' array: " + file.getName());
        }
        for (JsonElement elem : patternsArray) {
            JsonObject p = elem.getAsJsonObject();
            requireField(p, "id", "patterns entry", file);
            requireField(p, "rows", "patterns entry", file);
            Pattern pat = new Pattern(p.get("id").getAsInt(), p.get("rows").getAsInt());
            JsonObject tracks = p.getAsJsonObject("tracks");
            if (tracks != null) {
                for (String key : tracks.keySet()) {
                    int ch = Integer.parseInt(key);
                    String hex = tracks.get(key).getAsString();
                    if (!hex.isEmpty()) {
                        pat.setTrackData(ch, hexToBytes(hex));
                    }
                }
            }
            song.getPatterns().add(pat);
        }

        // DAC samples (optional for backward compatibility)
        if (root.has("dacSamples")) {
            for (JsonElement elem : root.getAsJsonArray("dacSamples")) {
                JsonObject s = elem.getAsJsonObject();
                requireField(s, "name", "dacSamples entry", file);
                requireField(s, "data", "dacSamples entry", file);
                requireField(s, "rate", "dacSamples entry", file);
                song.getDacSamples().add(new DacSample(
                        s.get("name").getAsString(),
                        hexToBytes(s.get("data").getAsString()),
                        s.get("rate").getAsInt()
                ));
            }
        }

        return song;
    }

    private static void requireField(JsonObject obj, String field, File file) throws IOException {
        if (obj.get(field) == null) {
            throw new IOException("Project file is missing required '" + field + "' field: " + file.getName());
        }
    }

    private static void requireField(JsonObject obj, String field, String context, File file) throws IOException {
        if (obj.get(field) == null) {
            throw new IOException("Missing required '" + field + "' in " + context + ": " + file.getName());
        }
    }
}
