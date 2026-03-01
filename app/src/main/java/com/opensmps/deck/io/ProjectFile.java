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

    private static final int VERSION = 2;
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
        root.addProperty("arrangementMode", song.getArrangementMode().name());
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

        // Structured arrangement (optional)
        StructuredArrangement structured = song.getStructuredArrangement();
        if (structured != null) {
            JsonObject structObj = new JsonObject();
            structObj.addProperty("ticksPerRow", structured.getTicksPerRow());

            JsonArray blocks = new JsonArray();
            for (BlockDefinition block : structured.getBlocks()) {
                JsonObject b = new JsonObject();
                b.addProperty("id", block.getId());
                b.addProperty("name", block.getName());
                b.addProperty("lengthTicks", block.getLengthTicks());
                JsonObject tracks = new JsonObject();
                for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
                    byte[] data = block.getTrackDataDirect(ch);
                    if (data != null && data.length > 0) {
                        tracks.addProperty(String.valueOf(ch), bytesToHex(data));
                    }
                }
                b.add("tracks", tracks);
                blocks.add(b);
            }
            structObj.add("blocks", blocks);

            JsonArray channels = new JsonArray();
            for (ChannelArrangement channel : structured.getChannels()) {
                JsonArray refs = new JsonArray();
                for (BlockRef ref : channel.getBlockRefs()) {
                    JsonObject r = new JsonObject();
                    r.addProperty("blockId", ref.getBlockId());
                    r.addProperty("startTick", ref.getStartTick());
                    r.addProperty("repeatCount", ref.getRepeatCount());
                    r.addProperty("transposeSemitones", ref.getTransposeSemitones());
                    refs.add(r);
                }
                channels.add(refs);
            }
            structObj.add("channels", channels);
            root.add("structuredArrangement", structObj);
        }

        // Hierarchical arrangement (optional)
        HierarchicalArrangement hierarchical = song.getHierarchicalArrangement();
        if (hierarchical != null) {
            JsonObject hierObj = new JsonObject();
            hierObj.addProperty("nextPhraseId", hierarchical.getPhraseLibrary().getNextId());

            JsonArray phrases = new JsonArray();
            for (Phrase phrase : hierarchical.getPhraseLibrary().getAllPhrases()) {
                JsonObject ph = new JsonObject();
                ph.addProperty("id", phrase.getId());
                ph.addProperty("name", phrase.getName());
                ph.addProperty("channelType", phrase.getChannelType().name());
                ph.addProperty("data", bytesToHex(phrase.getData()));

                if (!phrase.getSubPhraseRefs().isEmpty()) {
                    JsonArray refs = new JsonArray();
                    for (Phrase.SubPhraseRef ref : phrase.getSubPhraseRefs()) {
                        JsonObject r = new JsonObject();
                        r.addProperty("phraseId", ref.phraseId());
                        r.addProperty("insertAtRow", ref.insertAtRow());
                        r.addProperty("repeatCount", ref.repeatCount());
                        refs.add(r);
                    }
                    ph.add("subPhraseRefs", refs);
                }
                phrases.add(ph);
            }
            hierObj.add("phrases", phrases);

            JsonArray chains = new JsonArray();
            for (Chain chain : hierarchical.getChains()) {
                JsonObject ch = new JsonObject();
                ch.addProperty("channelIndex", chain.getChannelIndex());
                ch.addProperty("loopEntryIndex", chain.getLoopEntryIndex());

                JsonArray entries = new JsonArray();
                for (ChainEntry entry : chain.getEntries()) {
                    JsonObject ent = new JsonObject();
                    ent.addProperty("phraseId", entry.getPhraseId());
                    ent.addProperty("transposeSemitones", entry.getTransposeSemitones());
                    ent.addProperty("repeatCount", entry.getRepeatCount());
                    entries.add(ent);
                }
                ch.add("entries", entries);
                chains.add(ch);
            }
            hierObj.add("chains", chains);
            root.add("hierarchicalArrangement", hierObj);
        }

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
        if (fileVersion != VERSION) {
            throw new IOException(
                "Project file version " + fileVersion + " does not match supported version " + VERSION + ".");
        }

        Song song = new Song();
        // Clear defaults added by constructor
        song.getPatterns().clear();
        song.getOrderList().clear();

        requireField(root, "name", file);
        requireField(root, "smpsMode", file);
        requireField(root, "arrangementMode", file);
        requireField(root, "tempo", file);
        requireField(root, "dividingTiming", file);
        requireField(root, "loopPoint", file);

        song.setName(root.get("name").getAsString());
        song.setSmpsMode(SmpsMode.valueOf(root.get("smpsMode").getAsString()));
        try {
            song.setArrangementMode(ArrangementMode.valueOf(root.get("arrangementMode").getAsString()));
        } catch (IllegalArgumentException e) {
            // Unrecognized mode (e.g. removed LEGACY_PATTERNS) defaults to HIERARCHICAL
            song.setArrangementMode(ArrangementMode.HIERARCHICAL);
        }
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

        if (root.has("structuredArrangement")) {
            JsonObject structObj = root.getAsJsonObject("structuredArrangement");
            StructuredArrangement structured = new StructuredArrangement();
            if (structObj.has("ticksPerRow")) {
                structured.setTicksPerRow(structObj.get("ticksPerRow").getAsInt());
            }

            structured.getBlocks().clear();
            JsonArray blocks = structObj.getAsJsonArray("blocks");
            if (blocks != null) {
                for (JsonElement elem : blocks) {
                    JsonObject b = elem.getAsJsonObject();
                    int id = b.get("id").getAsInt();
                    String name = b.has("name") ? b.get("name").getAsString() : ("Block " + id);
                    int lengthTicks = b.has("lengthTicks") ? b.get("lengthTicks").getAsInt() : 0;
                    BlockDefinition block = new BlockDefinition(id, name, lengthTicks);
                    JsonObject tracks = b.getAsJsonObject("tracks");
                    if (tracks != null) {
                        for (String key : tracks.keySet()) {
                            int ch = Integer.parseInt(key);
                            String hex = tracks.get(key).getAsString();
                            if (!hex.isEmpty()) {
                                block.setTrackData(ch, hexToBytes(hex));
                            }
                        }
                    }
                    structured.getBlocks().add(block);
                }
            }

            JsonArray channels = structObj.getAsJsonArray("channels");
            if (channels != null) {
                int laneCount = Math.min(channels.size(), Pattern.CHANNEL_COUNT);
                for (int ch = 0; ch < laneCount; ch++) {
                    JsonArray refs = channels.get(ch).getAsJsonArray();
                    ChannelArrangement lane = structured.getChannels().get(ch);
                    lane.getBlockRefs().clear();
                    for (JsonElement refElem : refs) {
                        JsonObject r = refElem.getAsJsonObject();
                        BlockRef ref = new BlockRef();
                        if (r.has("blockId")) ref.setBlockId(r.get("blockId").getAsInt());
                        if (r.has("startTick")) ref.setStartTick(r.get("startTick").getAsInt());
                        if (r.has("repeatCount")) ref.setRepeatCount(r.get("repeatCount").getAsInt());
                        if (r.has("transposeSemitones")) ref.setTransposeSemitones(r.get("transposeSemitones").getAsInt());
                        lane.getBlockRefs().add(ref);
                    }
                }
            }
            song.setStructuredArrangement(structured);
        }

        if (root.has("hierarchicalArrangement")) {
            JsonObject hierObj = root.getAsJsonObject("hierarchicalArrangement");
            HierarchicalArrangement hierarchical = new HierarchicalArrangement();

            if (hierObj.has("nextPhraseId")) {
                hierarchical.getPhraseLibrary().setNextId(hierObj.get("nextPhraseId").getAsInt());
            }

            // Load phrases first so chains can reference them
            JsonArray phrases = hierObj.getAsJsonArray("phrases");
            if (phrases != null) {
                // Reset nextId to manually create phrases with correct IDs
                int maxId = 0;
                for (JsonElement elem : phrases) {
                    JsonObject ph = elem.getAsJsonObject();
                    int id = ph.get("id").getAsInt();
                    String name = ph.get("name").getAsString();
                    ChannelType channelType = ChannelType.valueOf(ph.get("channelType").getAsString());

                    // Use low-level creation to preserve IDs
                    hierarchical.getPhraseLibrary().setNextId(id);
                    Phrase phrase = hierarchical.getPhraseLibrary().createPhrase(name, channelType);
                    if (ph.has("data")) {
                        String hex = ph.get("data").getAsString();
                        if (!hex.isEmpty()) {
                            phrase.setData(hexToBytes(hex));
                        }
                    }

                    if (ph.has("subPhraseRefs")) {
                        for (JsonElement refElem : ph.getAsJsonArray("subPhraseRefs")) {
                            JsonObject r = refElem.getAsJsonObject();
                            phrase.getSubPhraseRefs().add(new Phrase.SubPhraseRef(
                                r.get("phraseId").getAsInt(),
                                r.get("insertAtRow").getAsInt(),
                                r.has("repeatCount") ? r.get("repeatCount").getAsInt() : 1
                            ));
                        }
                    }
                    maxId = Math.max(maxId, id);
                }
                // Restore nextId for future allocations
                if (hierObj.has("nextPhraseId")) {
                    hierarchical.getPhraseLibrary().setNextId(hierObj.get("nextPhraseId").getAsInt());
                } else {
                    hierarchical.getPhraseLibrary().setNextId(maxId + 1);
                }
            }

            // Load chains
            JsonArray chains = hierObj.getAsJsonArray("chains");
            if (chains != null) {
                int chainCount = Math.min(chains.size(), Pattern.CHANNEL_COUNT);
                for (int ch = 0; ch < chainCount; ch++) {
                    JsonObject chObj = chains.get(ch).getAsJsonObject();
                    Chain chain = hierarchical.getChain(ch);
                    chain.getEntries().clear();

                    if (chObj.has("loopEntryIndex")) {
                        chain.setLoopEntryIndex(chObj.get("loopEntryIndex").getAsInt());
                    }

                    JsonArray entries = chObj.getAsJsonArray("entries");
                    if (entries != null) {
                        for (JsonElement entElem : entries) {
                            JsonObject ent = entElem.getAsJsonObject();
                            ChainEntry entry = new ChainEntry(ent.get("phraseId").getAsInt());
                            if (ent.has("transposeSemitones")) {
                                entry.setTransposeSemitones(ent.get("transposeSemitones").getAsInt());
                            }
                            if (ent.has("repeatCount")) {
                                entry.setRepeatCount(ent.get("repeatCount").getAsInt());
                            }
                            chain.getEntries().add(entry);
                        }
                    }
                }
            }
            song.setHierarchicalArrangement(hierarchical);
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
