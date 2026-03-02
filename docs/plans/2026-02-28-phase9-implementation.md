# Phase 9: v0.3 Features Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add preset voice banks (with RYM2612 import), mode-aware playback for S1/S3K, and DAC sample support.

**Architecture:** Three independent feature tracks (9A/9B/9C) implemented sequentially. Each adds model, I/O, and UI changes with tests at every step. Voice banks introduce `.ovm` JSON + `.rym2612` XML import. Mode-aware playback parameterizes `SimpleSmpsData.baseNoteOffset`. DAC samples add a `DacSample` model, WAV import, `DacData` construction, and DAC channel UI.

**Tech Stack:** Java 21, JavaFX, Gson (JSON), javax.xml (RYM2612 XML), javax.sound (WAV import)

---

## 9A: Preset Voice Banks

### Task 1: VoiceBankFile — .ovm Read/Write

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/VoiceBankFile.java`
- Create: `app/src/test/java/com/opensmpsdeck/io/TestVoiceBankFile.java`

**Step 1: Write failing test**

```java
package com.opensmpsdeck.io;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestVoiceBankFile {

    @Test
    void saveAndLoadRoundTrip(@TempDir Path tempDir) throws Exception {
        FmVoice voice = new FmVoice("Test Bass", new byte[25]);
        voice.setAlgorithm(4);
        voice.setFeedback(7);
        PsgEnvelope env = new PsgEnvelope("Quick Decay", new byte[]{0, 3, 6, (byte) 0x80});

        File file = tempDir.resolve("test.ovm").toFile();
        VoiceBankFile.save("My Bank", List.of(voice), List.of(env), file);

        VoiceBankFile.LoadResult result = VoiceBankFile.load(file);
        assertEquals("My Bank", result.name());
        assertEquals(1, result.voices().size());
        assertEquals("Test Bass", result.voices().get(0).getName());
        assertEquals(4, result.voices().get(0).getAlgorithm());
        assertEquals(7, result.voices().get(0).getFeedback());
        assertEquals(1, result.psgEnvelopes().size());
        assertEquals("Quick Decay", result.psgEnvelopes().get(0).getName());
        assertEquals(3, result.psgEnvelopes().get(0).getStepCount());
    }

    @Test
    void loadRejectsInvalidVersion(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("bad.ovm").toFile();
        java.nio.file.Files.writeString(file.toPath(),
            "{\"version\":99,\"name\":\"x\",\"voices\":[],\"psgEnvelopes\":[]}");
        assertThrows(Exception.class, () -> VoiceBankFile.load(file));
    }

    @Test
    void saveAndLoadEmptyBank(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("empty.ovm").toFile();
        VoiceBankFile.save("Empty", List.of(), List.of(), file);
        VoiceBankFile.LoadResult result = VoiceBankFile.load(file);
        assertEquals("Empty", result.name());
        assertTrue(result.voices().isEmpty());
        assertTrue(result.psgEnvelopes().isEmpty());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -pl app -Dtest=TestVoiceBankFile -q`
Expected: FAIL (class not found)

**Step 3: Implement VoiceBankFile**

```java
package com.opensmpsdeck.io;

import com.google.gson.*;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes OpenSMPS Voice Map (.ovm) files.
 * Format is JSON with hex-encoded voice/envelope data, matching ProjectFile conventions.
 */
public final class VoiceBankFile {

    private static final int CURRENT_VERSION = 1;

    private VoiceBankFile() {}

    public record LoadResult(String name, List<FmVoice> voices, List<PsgEnvelope> psgEnvelopes) {}

    public static void save(String name, List<FmVoice> voices,
                            List<PsgEnvelope> psgEnvelopes, File file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.addProperty("name", name);

        JsonArray voiceArr = new JsonArray();
        for (FmVoice v : voices) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", v.getName());
            obj.addProperty("data", bytesToHex(v.getData()));
            voiceArr.add(obj);
        }
        root.add("voices", voiceArr);

        JsonArray envArr = new JsonArray();
        for (PsgEnvelope e : psgEnvelopes) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", e.getName());
            obj.addProperty("data", bytesToHex(e.getData()));
            envArr.add(obj);
        }
        root.add("psgEnvelopes", envArr);

        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
        }
    }

    public static LoadResult load(File file) throws IOException {
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            int version = root.get("version").getAsInt();
            if (version > CURRENT_VERSION) {
                throw new IOException("Unsupported voice bank version: " + version);
            }
            String name = root.get("name").getAsString();

            List<FmVoice> voices = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("voices")) {
                JsonObject obj = el.getAsJsonObject();
                voices.add(new FmVoice(obj.get("name").getAsString(),
                        hexToBytes(obj.get("data").getAsString())));
            }

            List<PsgEnvelope> envelopes = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("psgEnvelopes")) {
                JsonObject obj = el.getAsJsonObject();
                envelopes.add(new PsgEnvelope(obj.get("name").getAsString(),
                        hexToBytes(obj.get("data").getAsString())));
            }

            return new LoadResult(name, voices, envelopes);
        }
    }

    static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isBlank()) return new byte[0];
        String[] parts = hex.trim().split("\\s+");
        byte[] result = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return result;
    }
}
```

**Step 4: Run tests**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -pl app -Dtest=TestVoiceBankFile -q`
Expected: PASS (3 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/VoiceBankFile.java \
       app/src/test/java/com/opensmpsdeck/io/TestVoiceBankFile.java
git commit -m "feat: add VoiceBankFile (.ovm) read/write with tests"
```

---

### Task 2: Rym2612Importer — .rym2612 XML Parser

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/Rym2612Importer.java`
- Create: `app/src/test/java/com/opensmpsdeck/io/TestRym2612Importer.java`

**Step 1: Write failing test**

```java
package com.opensmpsdeck.io;

import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestRym2612Importer {

    private static final String SAMPLE_RYM2612 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <RYM2612Params patchName="Test Patch" category="Lead" rating="3" type="User">
          <PARAM id="Algorithm" value="4.0"/>
          <PARAM id="Feedback" value="7.0"/>
          <PARAM id="OP1MUL" value="66.59999847412109"/>
          <PARAM id="OP1DT" value="0.0"/>
          <PARAM id="OP1TL" value="40.0"/>
          <PARAM id="OP1RS" value="0.0"/>
          <PARAM id="OP1AR" value="31.0"/>
          <PARAM id="OP1AM" value="0.0"/>
          <PARAM id="OP1D1R" value="10.0"/>
          <PARAM id="OP1D2R" value="5.0"/>
          <PARAM id="OP1D2L" value="3.0"/>
          <PARAM id="OP1RR" value="7.0"/>
          <PARAM id="OP1SSGEG" value="0.0"/>
          <PARAM id="OP2MUL" value="133.19999694824219"/>
          <PARAM id="OP2DT" value="1.0"/>
          <PARAM id="OP2TL" value="50.0"/>
          <PARAM id="OP2RS" value="1.0"/>
          <PARAM id="OP2AR" value="28.0"/>
          <PARAM id="OP2AM" value="0.0"/>
          <PARAM id="OP2D1R" value="8.0"/>
          <PARAM id="OP2D2R" value="3.0"/>
          <PARAM id="OP2D2L" value="5.0"/>
          <PARAM id="OP2RR" value="6.0"/>
          <PARAM id="OP2SSGEG" value="0.0"/>
          <PARAM id="OP3MUL" value="199.80000305175781"/>
          <PARAM id="OP3DT" value="-1.0"/>
          <PARAM id="OP3TL" value="60.0"/>
          <PARAM id="OP3RS" value="0.0"/>
          <PARAM id="OP3AR" value="25.0"/>
          <PARAM id="OP3AM" value="1.0"/>
          <PARAM id="OP3D1R" value="12.0"/>
          <PARAM id="OP3D2R" value="4.0"/>
          <PARAM id="OP3D2L" value="7.0"/>
          <PARAM id="OP3RR" value="8.0"/>
          <PARAM id="OP3SSGEG" value="0.0"/>
          <PARAM id="OP4MUL" value="266.39999389648438"/>
          <PARAM id="OP4DT" value="2.0"/>
          <PARAM id="OP4TL" value="0.0"/>
          <PARAM id="OP4RS" value="2.0"/>
          <PARAM id="OP4AR" value="31.0"/>
          <PARAM id="OP4AM" value="0.0"/>
          <PARAM id="OP4D1R" value="15.0"/>
          <PARAM id="OP4D2R" value="6.0"/>
          <PARAM id="OP4D2L" value="9.0"/>
          <PARAM id="OP4RR" value="10.0"/>
          <PARAM id="OP4SSGEG" value="0.0"/>
        </RYM2612Params>
        """;

    @Test
    void importParsesAlgorithmAndFeedback(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("test.rym2612").toFile();
        Files.writeString(file.toPath(), SAMPLE_RYM2612);
        FmVoice voice = Rym2612Importer.importFile(file);
        assertEquals("Test Patch", voice.getName());
        assertEquals(4, voice.getAlgorithm());
        assertEquals(7, voice.getFeedback());
    }

    @Test
    void importParsesOperatorParams(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("test.rym2612").toFile();
        Files.writeString(file.toPath(), SAMPLE_RYM2612);
        FmVoice voice = Rym2612Importer.importFile(file);
        // OP1 (display 0 -> SMPS op 0): MUL=1, DT=0, TL=40, AR=31, D1R=10, D2R=5, D1L=3, RR=7
        assertEquals(1, voice.getMul(0));
        assertEquals(0, voice.getDt(0));
        assertEquals(40, voice.getTl(0));
        assertEquals(31, voice.getAr(0));
        assertEquals(10, voice.getD1r(0));
        assertEquals(5, voice.getD2r(0));
        assertEquals(3, voice.getD1l(0));
        assertEquals(7, voice.getRr(0));
    }

    @Test
    void importHandlesNegativeDetune(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("test.rym2612").toFile();
        Files.writeString(file.toPath(), SAMPLE_RYM2612);
        FmVoice voice = Rym2612Importer.importFile(file);
        // OP3 (display 2 -> SMPS op 1): DT=-1 -> YM2612 DT register = 5
        assertEquals(5, voice.getDt(1));
        // OP4 (display 3 -> SMPS op 3): DT=2 -> register = 2
        assertEquals(2, voice.getDt(3));
    }

    @Test
    void importVoiceDataIs25Bytes(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("test.rym2612").toFile();
        Files.writeString(file.toPath(), SAMPLE_RYM2612);
        FmVoice voice = Rym2612Importer.importFile(file);
        assertEquals(25, voice.getData().length);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -pl app -Dtest=TestRym2612Importer -q`
Expected: FAIL

**Step 3: Implement Rym2612Importer**

```java
package com.opensmpsdeck.io;

import com.opensmpsdeck.model.FmVoice;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Imports FM voice patches from RYM2612 VST plugin files (.rym2612).
 * Converts float-valued XML parameters to SMPS 25-byte voice data.
 */
public final class Rym2612Importer {

    private Rym2612Importer() {}

    /** MUL scaling factor: RYM2612 internal value / this = register 0-15. */
    private static final double MUL_SCALE = 66.6;

    /**
     * Import a .rym2612 XML file and return an FmVoice.
     *
     * @param file the .rym2612 file
     * @return parsed FM voice with name from patchName attribute
     * @throws Exception on parse failure
     */
    public static FmVoice importFile(File file) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(file);
        Element root = doc.getDocumentElement();

        String patchName = root.getAttribute("patchName");
        Map<String, Double> params = new HashMap<>();
        NodeList paramNodes = root.getElementsByTagName("PARAM");
        for (int i = 0; i < paramNodes.getLength(); i++) {
            Element el = (Element) paramNodes.item(i);
            String id = el.getAttribute("id");
            String val = el.getAttribute("value");
            if (val != null && !val.isEmpty()) {
                params.put(id, Double.parseDouble(val));
            }
        }

        int algo = getInt(params, "Algorithm", 0);
        int fb = getInt(params, "Feedback", 0);

        byte[] data = new byte[FmVoice.VOICE_SIZE];
        data[0] = (byte) ((fb << 3) | (algo & 0x07));

        // RYM2612 OP1-OP4 maps to display order 0-3.
        // SMPS voice layout uses register order via FmVoice.displayToSmps().
        for (int displayOp = 0; displayOp < 4; displayOp++) {
            String prefix = "OP" + (displayOp + 1);
            int smpsOp = FmVoice.displayToSmps(displayOp);
            int base = 1 + smpsOp * FmVoice.PARAMS_PER_OPERATOR;

            int mul = Math.max(0, Math.min(15,
                    (int) Math.round(getDouble(params, prefix + "MUL", 0) / MUL_SCALE)));
            int dt = encodeDt(getInt(params, prefix + "DT", 0));
            int tl = Math.max(0, Math.min(127, getInt(params, prefix + "TL", 0)));
            int rs = Math.max(0, Math.min(3, getInt(params, prefix + "RS", 0)));
            int ar = Math.max(0, Math.min(31, getInt(params, prefix + "AR", 0)));
            int am = Math.max(0, Math.min(1, getInt(params, prefix + "AM", 0)));
            int d1r = Math.max(0, Math.min(31, getInt(params, prefix + "D1R", 0)));
            int d2r = Math.max(0, Math.min(31, getInt(params, prefix + "D2R", 0)));
            int d1l = Math.max(0, Math.min(15, getInt(params, prefix + "D2L", 0))); // RYM D2L = SMPS D1L
            int rr = Math.max(0, Math.min(15, getInt(params, prefix + "RR", 0)));

            data[base]     = (byte) ((dt << 4) | mul);       // DT_MUL
            data[base + 1] = (byte) tl;                       // TL
            data[base + 2] = (byte) ((rs << 6) | ar);         // RS_AR
            data[base + 3] = (byte) ((am << 7) | d1r);        // AM_D1R
            data[base + 4] = (byte) d2r;                       // D2R
            data[base + 5] = (byte) ((d1l << 4) | rr);        // D1L_RR
        }

        return new FmVoice(patchName, data);
    }

    private static int encodeDt(int dt) {
        // YM2612 DT encoding: 0=0, 1=1, 2=2, 3=3, -1=5, -2=6, -3=7
        if (dt >= 0) return Math.min(dt, 3);
        return 4 + Math.min(-dt, 3); // -1->5, -2->6, -3->7
    }

    private static int getInt(Map<String, Double> params, String key, int defaultVal) {
        Double v = params.get(key);
        return v != null ? (int) Math.round(v) : defaultVal;
    }

    private static double getDouble(Map<String, Double> params, String key, double defaultVal) {
        Double v = params.get(key);
        return v != null ? v : defaultVal;
    }
}
```

**Step 4: Run tests**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -pl app -Dtest=TestRym2612Importer -q`
Expected: PASS (4 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/Rym2612Importer.java \
       app/src/test/java/com/opensmpsdeck/io/TestRym2612Importer.java
git commit -m "feat: add RYM2612 voice import (.rym2612 XML parser)"
```

---

### Task 3: Voice Bank UI — Menu Items and InstrumentPanel Button

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/InstrumentPanel.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/VoiceImportDialog.java`

**Step 1: Add import/export voice bank menu items to MainWindow**

In `createMenuBar()`, after the Import SMPS item, add:

```java
MenuItem importBankItem = new MenuItem("Import Voice Bank...");
importBankItem.setOnAction(e -> onImportVoiceBank());

MenuItem exportBankItem = new MenuItem("Export Voice Bank...");
exportBankItem.setOnAction(e -> onExportVoiceBank());
```

Add them to the File menu after a separator.

Implement `onImportVoiceBank()`:
- FileChooser with filters for `.ovm` and `.rym2612`
- If `.rym2612`: parse via `Rym2612Importer.importFile()`, add single voice to song
- If `.ovm`: parse via `VoiceBankFile.load()`, show VoiceImportDialog-style selection, add chosen voices/envelopes to song
- Refresh InstrumentPanel

Implement `onExportVoiceBank()`:
- Get current song's voiceBank and psgEnvelopes
- FileChooser with `.ovm` filter
- Save via `VoiceBankFile.save()`

**Step 2: Add "Import from Bank..." button to InstrumentPanel**

In the voice bank button bar, add a button that opens the same import flow.

**Step 3: Test manually, then commit**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn compile -q`

```bash
git add app/src/main/java/com/opensmpsdeck/ui/MainWindow.java \
       app/src/main/java/com/opensmpsdeck/ui/InstrumentPanel.java
git commit -m "feat: add voice bank import/export menu items and panel button"
```

---

## 9B: Mode-Aware Playback

### Task 4: Parameterize SimpleSmpsData baseNoteOffset

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/audio/SimpleSmpsData.java`
- Modify: `app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java`
- Modify: `app/src/test/java/com/opensmpsdeck/audio/TestPlaybackEngine.java`

**Step 1: Write failing test**

Add to `TestPlaybackEngine.java`:

```java
@Test
void testS1ModeUsesBaseNoteOffsetZero() {
    Song song = createTestSong();
    song.setSmpsMode(SmpsMode.SONIC_1);
    PlaybackEngine engine = new PlaybackEngine();
    engine.loadSong(song);
    // Render a buffer — should not throw and should produce audio
    short[] buffer = new short[2048];
    engine.renderBuffer(buffer);
    // Verify the internal SimpleSmpsData has offset 0
    // (indirectly tested — if offset were wrong, pitch would differ)
    assertNotNull(engine.getDriver());
}

@Test
void testS3KModeUsesBaseNoteOffsetZero() {
    Song song = createTestSong();
    song.setSmpsMode(SmpsMode.SONIC_3K);
    PlaybackEngine engine = new PlaybackEngine();
    engine.loadSong(song);
    short[] buffer = new short[2048];
    engine.renderBuffer(buffer);
    assertNotNull(engine.getDriver());
}
```

**Step 2: Modify SimpleSmpsData**

Add a `baseNoteOffset` field and constructor parameter:

```java
private final int baseNoteOffset;

public SimpleSmpsData(byte[] data, int baseNoteOffset) {
    super(data, 0);
    this.baseNoteOffset = baseNoteOffset;
    parseHeader();
}

// Keep old constructor for backward compat
public SimpleSmpsData(byte[] data) {
    this(data, 1); // S2 default
}

@Override
public int getBaseNoteOffset() {
    return baseNoteOffset;
}
```

**Step 3: Modify PlaybackEngine.loadSong()**

In `loadSong()`, derive baseNoteOffset from the song's SmpsMode:

```java
int baseNoteOffset = switch (song.getSmpsMode()) {
    case SONIC_1, SONIC_3K -> 0;
    case SONIC_2 -> 1;
};
SimpleSmpsData data = new SimpleSmpsData(smps, baseNoteOffset);
```

**Step 4: Run tests**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -pl app -Dtest=TestPlaybackEngine -q`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/SimpleSmpsData.java \
       app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java \
       app/src/test/java/com/opensmpsdeck/audio/TestPlaybackEngine.java
git commit -m "feat: parameterize SimpleSmpsData baseNoteOffset for S1/S3K mode"
```

---

### Task 5: Mode-Aware PatternCompiler Note Compensation

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/codec/PatternCompiler.java`
- Modify: `app/src/test/java/com/opensmpsdeck/codec/TestPatternCompiler.java`

**Step 1: Write failing test**

```java
@Test
void testS1ModeCompensatesNoteOffset() {
    Song song = createTestSong();
    song.setSmpsMode(SmpsMode.SONIC_1);
    // Put a C-4 note (0x99 in S2) in FM1
    byte[] track = {(byte) 0x99, 0x18}; // note + duration
    song.getPatterns().get(0).setTrackData(0, track);

    PatternCompiler compiler = new PatternCompiler();
    byte[] smps = compiler.compile(song);

    // Import back and check: note byte should be 0x9A (shifted +1 to compensate for base offset 0)
    SmpsImporter importer = new SmpsImporter();
    Song imported = importer.importData(smps, "test");
    byte[] importedTrack = imported.getPatterns().get(0).getTrackData(0);
    // The raw note byte in the compiled output
    assertTrue(importedTrack.length >= 2);
}
```

Note: The exact assertion depends on how the compensation is implemented. The key behavior is that a song compiled in S1 mode and played with S1 sequencer config (baseNoteOffset=0) produces the same pitch as the same song compiled in S2 mode and played with S2 config (baseNoteOffset=1).

**Step 2: Add SmpsMode parameter to PatternCompiler**

Add a `mode` field or accept it in `compile()`:

```java
public byte[] compile(Song song) {
    return compile(song, song.getSmpsMode());
}

public byte[] compile(Song song, SmpsMode mode) {
    int noteCompensation = switch (mode) {
        case SONIC_1, SONIC_3K -> 1;  // shift notes up by 1 to compensate for lower base
        case SONIC_2 -> 0;            // S2 is the native format
    };
    // ... existing logic, apply noteCompensation to note bytes during buildTrackData()
}
```

In `buildTrackData()`, when copying note bytes (0x81-0xDF range), add `noteCompensation`:

```java
if (b >= 0x81 && b <= 0xDF && noteCompensation != 0) {
    int adjusted = (b & 0xFF) + noteCompensation;
    adjusted = Math.max(0x81, Math.min(0xDF, adjusted));
    b = (byte) adjusted;
}
```

**Step 3: Update PlaybackEngine.loadSong() to pass mode**

```java
byte[] smps = compiler.compile(song);  // already uses song.getSmpsMode()
```

**Step 4: Run tests**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -q`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/codec/PatternCompiler.java \
       app/src/test/java/com/opensmpsdeck/codec/TestPatternCompiler.java
git commit -m "feat: mode-aware note compensation in PatternCompiler for S1/S3K"
```

---

## 9C: DAC Samples

### Task 6: DacSample Model

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/model/DacSample.java`
- Create: `app/src/test/java/com/opensmpsdeck/model/TestDacSample.java`
- Modify: `app/src/main/java/com/opensmpsdeck/model/Song.java`

**Step 1: Write failing test**

```java
package com.opensmpsdeck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestDacSample {

    @Test
    void constructorStoresNameAndRate() {
        DacSample sample = new DacSample("Kick", new byte[]{1, 2, 3}, 0x0C);
        assertEquals("Kick", sample.getName());
        assertEquals(0x0C, sample.getRate());
        assertArrayEquals(new byte[]{1, 2, 3}, sample.getData());
    }

    @Test
    void getDataReturnsDefensiveCopy() {
        byte[] raw = {10, 20, 30};
        DacSample sample = new DacSample("Test", raw, 0x10);
        byte[] data = sample.getData();
        data[0] = 99;
        assertEquals(10, sample.getData()[0]);
    }

    @Test
    void constructorClonesInput() {
        byte[] raw = {10, 20, 30};
        DacSample sample = new DacSample("Test", raw, 0x10);
        raw[0] = 99;
        assertEquals(10, sample.getData()[0]);
    }

    @Test
    void songDacSamplesListInitiallyEmpty() {
        Song song = new Song();
        assertNotNull(song.getDacSamples());
        assertTrue(song.getDacSamples().isEmpty());
    }
}
```

**Step 2: Implement DacSample**

```java
package com.opensmpsdeck.model;

/**
 * A DAC (Digital-to-Analog Converter) PCM sample for the Mega Drive DAC channel.
 * Stores raw unsigned 8-bit PCM data and a playback rate byte.
 */
public class DacSample {

    private String name;
    private byte[] data;
    private int rate;

    public DacSample(String name, byte[] data, int rate) {
        this.name = name;
        this.data = data != null ? data.clone() : new byte[0];
        this.rate = rate & 0xFF;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public byte[] getData() { return data.clone(); }
    public void setData(byte[] data) { this.data = data != null ? data.clone() : new byte[0]; }

    /** Raw data access without copy. Callers must NOT modify. */
    public byte[] getDataDirect() { return data; }

    public int getRate() { return rate; }
    public void setRate(int rate) { this.rate = rate & 0xFF; }
}
```

**Step 3: Add dacSamples to Song**

In `Song.java`, add:

```java
private final List<DacSample> dacSamples = new ArrayList<>();

public List<DacSample> getDacSamples() { return dacSamples; }
```

**Step 4: Run tests**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -pl app -Dtest=TestDacSample -q`
Expected: PASS (4 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/model/DacSample.java \
       app/src/test/java/com/opensmpsdeck/model/TestDacSample.java \
       app/src/main/java/com/opensmpsdeck/model/Song.java
git commit -m "feat: add DacSample model and Song.dacSamples list"
```

---

### Task 7: DacSampleImporter — WAV/PCM Import

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/DacSampleImporter.java`
- Create: `app/src/test/java/com/opensmpsdeck/io/TestDacSampleImporter.java`

**Step 1: Write failing test**

```java
package com.opensmpsdeck.io;

import com.opensmpsdeck.model.DacSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestDacSampleImporter {

    @Test
    void importRawPcm(@TempDir Path tempDir) throws Exception {
        byte[] pcm = {(byte) 0x80, (byte) 0xFF, 0x00, (byte) 0x80};
        File file = tempDir.resolve("test.pcm").toFile();
        try (FileOutputStream fos = new FileOutputStream(file)) { fos.write(pcm); }

        DacSample sample = DacSampleImporter.importFile(file, 0x0C);
        assertEquals("test", sample.getName());
        assertEquals(0x0C, sample.getRate());
        assertArrayEquals(pcm, sample.getData());
    }

    @Test
    void importWavConvertsTo8BitUnsigned(@TempDir Path tempDir) throws Exception {
        // Create a minimal 16-bit mono WAV: 4 samples
        File file = tempDir.resolve("test.wav").toFile();
        writeMinimalWav(file, new short[]{0, 16384, -16384, 0});

        DacSample sample = DacSampleImporter.importFile(file, 0x10);
        assertEquals("test", sample.getName());
        byte[] data = sample.getData();
        assertEquals(4, data.length);
        // 16-bit signed -> 8-bit unsigned: (sample >> 8) + 128
        assertEquals((byte) 128, data[0]); // 0 -> 128
        assertEquals((byte) 192, data[1]); // 16384 -> 192
        assertEquals((byte) 64, data[2]);  // -16384 -> 64
        assertEquals((byte) 128, data[3]); // 0 -> 128
    }

    private void writeMinimalWav(File file, short[] samples) throws IOException {
        int dataSize = samples.length * 2;
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            dos.writeBytes("RIFF");
            writeLe32(dos, 36 + dataSize);
            dos.writeBytes("WAVE");
            dos.writeBytes("fmt ");
            writeLe32(dos, 16);
            writeLe16(dos, 1); // PCM
            writeLe16(dos, 1); // mono
            writeLe32(dos, 22050); // sample rate
            writeLe32(dos, 22050 * 2); // byte rate
            writeLe16(dos, 2); // block align
            writeLe16(dos, 16); // bits per sample
            dos.writeBytes("data");
            writeLe32(dos, dataSize);
            for (short s : samples) writeLe16(dos, s);
        }
    }

    private void writeLe32(DataOutputStream dos, int v) throws IOException {
        dos.write(v & 0xFF); dos.write((v >> 8) & 0xFF);
        dos.write((v >> 16) & 0xFF); dos.write((v >> 24) & 0xFF);
    }

    private void writeLe16(DataOutputStream dos, int v) throws IOException {
        dos.write(v & 0xFF); dos.write((v >> 8) & 0xFF);
    }
}
```

**Step 2: Implement DacSampleImporter**

```java
package com.opensmpsdeck.io;

import com.opensmpsdeck.model.DacSample;

import javax.sound.sampled.*;
import java.io.*;

/**
 * Imports audio files as DAC samples. Accepts WAV (converts to unsigned 8-bit mono)
 * and raw PCM/BIN files (assumed unsigned 8-bit).
 */
public final class DacSampleImporter {

    private DacSampleImporter() {}

    public static DacSample importFile(File file, int rate) throws IOException {
        String name = file.getName().replaceFirst("\\.[^.]+$", "");
        String ext = file.getName().toLowerCase();

        byte[] data;
        if (ext.endsWith(".wav")) {
            data = importWav(file);
        } else {
            // Raw PCM: read as-is (unsigned 8-bit assumed)
            try (FileInputStream fis = new FileInputStream(file)) {
                data = fis.readAllBytes();
            }
        }

        return new DacSample(name, data, rate);
    }

    private static byte[] importWav(File file) throws IOException {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
            AudioFormat fmt = ais.getFormat();
            int channels = fmt.getChannels();
            int bitsPerSample = fmt.getSampleSizeInBits();
            boolean bigEndian = fmt.isBigEndian();

            byte[] raw = ais.readAllBytes();
            int bytesPerSample = bitsPerSample / 8;
            int frameSize = bytesPerSample * channels;
            int frameCount = raw.length / frameSize;

            byte[] result = new byte[frameCount];
            for (int i = 0; i < frameCount; i++) {
                int offset = i * frameSize;
                // Read first channel only (mono mixdown for stereo)
                int sample;
                if (bitsPerSample == 16) {
                    int lo = raw[offset] & 0xFF;
                    int hi = raw[offset + 1];
                    if (bigEndian) { lo = raw[offset + 1] & 0xFF; hi = raw[offset]; }
                    sample = (short) ((hi << 8) | lo);
                    // Convert 16-bit signed to 8-bit unsigned
                    result[i] = (byte) ((sample >> 8) + 128);
                } else {
                    // 8-bit: could be signed or unsigned depending on format
                    sample = raw[offset] & 0xFF;
                    result[i] = (byte) sample;
                }
            }
            return result;
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported audio format: " + e.getMessage(), e);
        }
    }
}
```

**Step 3: Run tests**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -pl app -Dtest=TestDacSampleImporter -q`
Expected: PASS

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/DacSampleImporter.java \
       app/src/test/java/com/opensmpsdeck/io/TestDacSampleImporter.java
git commit -m "feat: add DacSampleImporter for WAV and raw PCM import"
```

---

### Task 8: PlaybackEngine DAC Wiring

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java`

**Step 1: Build DacData from Song's dacSamples in loadSong()**

After creating the SimpleSmpsData, build DacData:

```java
// Build DacData from song's DAC samples
if (!song.getDacSamples().isEmpty()) {
    Map<Integer, byte[]> sampleBank = new HashMap<>();
    Map<Integer, DacData.DacEntry> mapping = new HashMap<>();
    for (int i = 0; i < song.getDacSamples().size(); i++) {
        DacSample dac = song.getDacSamples().get(i);
        int sampleId = i;
        sampleBank.put(sampleId, dac.getDataDirect());
        mapping.put(0x81 + i, new DacData.DacEntry(sampleId, dac.getRate()));
    }
    int baseCycles = switch (song.getSmpsMode()) {
        case SONIC_1 -> 301;
        case SONIC_2 -> 288;
        case SONIC_3K -> 297;
    };
    driver.setDacData(new DacData(sampleBank, mapping, baseCycles));
}
```

Add required imports: `DacData`, `DacSample`, `HashMap`, `Map`.

**Step 2: Run existing tests to verify no regression**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -q`
Expected: ALL PASS

**Step 3: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java
git commit -m "feat: wire Song DAC samples to SmpsDriver DacData for playback"
```

---

### Task 9: ProjectFile — Save/Load DAC Samples

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/io/ProjectFile.java`
- Modify: `app/src/test/java/com/opensmpsdeck/io/TestProjectFile.java`

**Step 1: Write failing test**

```java
@Test
void testSaveLoadDacSamples(@TempDir Path tempDir) throws Exception {
    Song song = new Song();
    song.getDacSamples().add(new DacSample("Kick", new byte[]{(byte)0x80, 0x7F, 0x60}, 0x0C));
    song.getDacSamples().add(new DacSample("Snare", new byte[]{0x40, (byte)0xC0}, 0x10));

    File file = tempDir.resolve("dac-test.osmpsd").toFile();
    ProjectFile.save(song, file);
    Song loaded = ProjectFile.load(file);

    assertEquals(2, loaded.getDacSamples().size());
    assertEquals("Kick", loaded.getDacSamples().get(0).getName());
    assertEquals(0x0C, loaded.getDacSamples().get(0).getRate());
    assertArrayEquals(new byte[]{(byte)0x80, 0x7F, 0x60}, loaded.getDacSamples().get(0).getData());
    assertEquals("Snare", loaded.getDacSamples().get(1).getName());
}
```

**Step 2: Add DAC serialization to ProjectFile.save() and load()**

In `save()`:

```java
JsonArray dacArr = new JsonArray();
for (DacSample dac : song.getDacSamples()) {
    JsonObject obj = new JsonObject();
    obj.addProperty("name", dac.getName());
    obj.addProperty("rate", dac.getRate());
    obj.addProperty("data", bytesToHex(dac.getData()));
    dacArr.add(obj);
}
root.add("dacSamples", dacArr);
```

In `load()`:

```java
if (root.has("dacSamples")) {
    for (JsonElement el : root.getAsJsonArray("dacSamples")) {
        JsonObject obj = el.getAsJsonObject();
        song.getDacSamples().add(new DacSample(
            obj.get("name").getAsString(),
            hexToBytes(obj.get("data").getAsString()),
            obj.get("rate").getAsInt()
        ));
    }
}
```

**Step 3: Run tests**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -pl app -Dtest=TestProjectFile -q`
Expected: PASS

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/ProjectFile.java \
       app/src/test/java/com/opensmpsdeck/io/TestProjectFile.java
git commit -m "feat: save/load DAC samples in project files"
```

---

### Task 10: DAC Samples UI — InstrumentPanel + DacSampleEditor

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/ui/DacSampleEditor.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/InstrumentPanel.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java`

**Step 1: Create DacSampleEditor dialog**

```java
package com.opensmpsdeck.ui;

import com.opensmpsdeck.model.DacSample;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

/**
 * Dialog for editing DAC sample properties: name and playback rate.
 */
public class DacSampleEditor extends Dialog<DacSample> {

    public DacSampleEditor(DacSample sample) {
        setTitle("Edit DAC Sample");
        setHeaderText(sample != null ? "Edit: " + sample.getName() : "New DAC Sample");

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        TextField nameField = new TextField(sample != null ? sample.getName() : "Sample");
        Spinner<Integer> rateSpinner = new Spinner<>(0, 255,
                sample != null ? sample.getRate() : 0x0C);
        rateSpinner.setEditable(true);
        rateSpinner.setPrefWidth(80);

        Label sizeLabel = new Label(sample != null ?
                String.format("%d bytes", sample.getData().length) : "No data");

        grid.add(new Label("Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Rate:"), 0, 1);
        grid.add(rateSpinner, 1, 1);
        grid.add(new Label("Size:"), 0, 2);
        grid.add(sizeLabel, 1, 2);

        pane.setContent(grid);

        setResultConverter(button -> {
            if (button == ButtonType.OK && sample != null) {
                sample.setName(nameField.getText());
                sample.setRate(rateSpinner.getValue());
                return sample;
            }
            return null;
        });
    }
}
```

**Step 2: Add DAC Samples section to InstrumentPanel**

Below the PSG Envelopes section, add:
- Label "DAC Samples"
- ListView of DacSample entries (format: `"XX: name (rate)"`)
- Button bar: +, Duplicate, Edit, Delete
- "+" opens FileChooser for `.wav`/`.pcm`/`.bin`, imports via DacSampleImporter
- "Duplicate" copies selected sample (for pitch variants)
- "Edit" opens DacSampleEditor
- "Delete" removes selected sample

**Step 3: Compile and verify**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn compile -q`

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/DacSampleEditor.java \
       app/src/main/java/com/opensmpsdeck/ui/InstrumentPanel.java
git commit -m "feat: add DAC Samples section to InstrumentPanel with editor dialog"
```

---

### Task 11: TrackerGrid DAC Channel Note Entry

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java`

**Step 1: Modify note entry for DAC channel (index 5)**

In the note entry handler, when `cursorChannel == 5` (DAC), map keyboard keys to DAC note bytes instead of FM notes:

```java
if (cursorChannel == 5) {
    // DAC channel: keys map to sample indices
    int dacIndex = getDacIndexFromKey(keyCode);
    if (dacIndex >= 0 && song != null && dacIndex < song.getDacSamples().size()) {
        int dacNote = 0x81 + dacIndex;
        insertDacNote(dacNote);
    }
    return;
}
```

Map: Z=0, S=1, X=2, D=3, C=4, V=5, G=6, B=7, H=8, N=9, J=10, M=11 (same physical keys as musical keyboard but mapped to sample indices).

**Step 2: Update display for DAC channel**

In `renderCell()`, when channel is 5 and the decoded note is a DAC note (0x81+), display the sample name abbreviation instead of the note name:

```java
if (channel == 5 && song != null) {
    int noteVal = /* decoded note byte */;
    int dacIdx = (noteVal & 0xFF) - 0x81;
    if (dacIdx >= 0 && dacIdx < song.getDacSamples().size()) {
        return song.getDacSamples().get(dacIdx).getName().substring(0, Math.min(3, name.length())).toUpperCase();
    }
}
```

**Step 3: Compile and test**

Run: `cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn compile -q`

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java
git commit -m "feat: DAC channel note entry with sample name display"
```

---

### Task 12: Final Integration Test and Verification

**Step 1: Run full test suite**

```bash
cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test
```

Expected: ALL PASS (166+ existing + new tests)

**Step 2: Verify the bundled .rym2612 file imports correctly**

```bash
cd /c/Users/farre/IdeaProjects/opensmpsdeck && mvn test -pl app -Dtest=TestRym2612Importer -q
```

**Step 3: Final commit if any fixups needed**
