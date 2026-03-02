# Voice Presets & ADSR Envelope Preview Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `.osmpsvoice` single-voice preset files and visual ADSR envelope preview curves in the FM voice editor.

**Architecture:** Two independent feature tracks. Track A adds `OsmpsVoiceFile` I/O class + UI integration (FmVoiceEditor buttons, InstrumentPanel export, import filter). Track B adds `AdsrEnvelopeCalculator` model class + envelope Canvas in FmVoiceEditor. Both tracks modify `FmVoiceEditor.java` but in non-overlapping regions (button bar vs. layout/canvas).

**Tech Stack:** Java 21, Gson (JSON I/O), JavaFX Canvas (envelope drawing), JUnit 5

---

## Track A: `.osmpsvoice` Preset Files

### Task 1: OsmpsVoiceFile — save/load round-trip test

**Files:**
- Create: `app/src/test/java/com/opensmpsdeck/io/TestOsmpsVoiceFile.java`
- Create: `app/src/main/java/com/opensmpsdeck/io/OsmpsVoiceFile.java`

**Step 1: Write the failing test**

```java
package com.opensmpsdeck.io;

import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class TestOsmpsVoiceFile {

    @TempDir
    File tempDir;

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x3C; // algo=4, fb=7
        voiceData[1] = 0x71;
        voiceData[2] = 0x22;
        FmVoice voice = new FmVoice("BrassLead", voiceData);

        File file = new File(tempDir, "test.osmpsvoice");
        OsmpsVoiceFile.save(voice, file);
        assertTrue(file.exists());
        assertTrue(file.length() > 0);

        FmVoice loaded = OsmpsVoiceFile.load(file);
        assertEquals("BrassLead", loaded.getName());
        assertEquals(4, loaded.getAlgorithm());
        assertEquals(7, loaded.getFeedback());
        assertArrayEquals(voiceData, loaded.getData());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestOsmpsVoiceFile`
Expected: FAIL — `OsmpsVoiceFile` class does not exist.

**Step 3: Write minimal implementation**

```java
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
 *   "name": "...",
 *   "data": "hex"
 * }
 * }</pre>
 */
public final class OsmpsVoiceFile {

    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private OsmpsVoiceFile() {}

    /**
     * Saves a single FM voice to an {@code .osmpsvoice} file.
     */
    public static void save(FmVoice voice, File file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("name", voice.getName());
        root.addProperty("data", bytesToHex(voice.getData()));
        Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    /**
     * Loads a single FM voice from an {@code .osmpsvoice} file.
     */
    public static FmVoice load(File file) throws IOException {
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        JsonElement versionElem = root.get("version");
        if (versionElem == null) {
            throw new IOException("Voice preset is missing 'version' field: " + file.getName());
        }
        int fileVersion = versionElem.getAsInt();
        if (fileVersion > VERSION) {
            throw new IOException(
                    "Voice preset version " + fileVersion + " is newer than supported version "
                    + VERSION + ". Please update OpenSMPSDeck.");
        }

        JsonElement nameElem = root.get("name");
        if (nameElem == null) {
            throw new IOException("Voice preset is missing 'name' field: " + file.getName());
        }

        JsonElement dataElem = root.get("data");
        if (dataElem == null) {
            throw new IOException("Voice preset is missing 'data' field: " + file.getName());
        }

        byte[] data = hexToBytes(dataElem.getAsString());
        if (data.length != FmVoice.VOICE_SIZE) {
            throw new IOException("Voice preset data is " + data.length
                    + " bytes, expected " + FmVoice.VOICE_SIZE + ": " + file.getName());
        }

        return new FmVoice(nameElem.getAsString(), data);
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestOsmpsVoiceFile`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/OsmpsVoiceFile.java \
       app/src/test/java/com/opensmpsdeck/io/TestOsmpsVoiceFile.java
git commit -m "feat: add OsmpsVoiceFile for single FM voice preset I/O"
```

---

### Task 2: OsmpsVoiceFile — error handling tests

**Files:**
- Modify: `app/src/test/java/com/opensmpsdeck/io/TestOsmpsVoiceFile.java`

**Step 1: Add failing tests for error cases**

Append to `TestOsmpsVoiceFile`:

```java
@Test
void loadRejectsUnsupportedVersion() throws Exception {
    String json = """
            {"version": 99, "name": "Bad", "data": "00"}
            """;
    File file = new File(tempDir, "bad.osmpsvoice");
    java.nio.file.Files.writeString(file.toPath(), json, java.nio.charset.StandardCharsets.UTF_8);

    IOException ex = assertThrows(IOException.class, () -> OsmpsVoiceFile.load(file));
    assertTrue(ex.getMessage().contains("99"));
}

@Test
void loadRejectsMissingData() throws Exception {
    String json = """
            {"version": 1, "name": "NoData"}
            """;
    File file = new File(tempDir, "nodata.osmpsvoice");
    java.nio.file.Files.writeString(file.toPath(), json, java.nio.charset.StandardCharsets.UTF_8);

    assertThrows(IOException.class, () -> OsmpsVoiceFile.load(file));
}

@Test
void loadRejectsWrongDataLength() throws Exception {
    String json = """
            {"version": 1, "name": "Short", "data": "00 01 02"}
            """;
    File file = new File(tempDir, "short.osmpsvoice");
    java.nio.file.Files.writeString(file.toPath(), json, java.nio.charset.StandardCharsets.UTF_8);

    IOException ex = assertThrows(IOException.class, () -> OsmpsVoiceFile.load(file));
    assertTrue(ex.getMessage().contains("3 bytes"));
}

@Test
void loadRejectsCorruptJson() throws Exception {
    File file = new File(tempDir, "corrupt.osmpsvoice");
    java.nio.file.Files.writeString(file.toPath(), "not json {{{", java.nio.charset.StandardCharsets.UTF_8);

    assertThrows(Exception.class, () -> OsmpsVoiceFile.load(file));
}

@Test
void specialCharactersInNamePreserved() throws IOException {
    byte[] data = new byte[25];
    data[0] = 0x1A;
    FmVoice voice = new FmVoice("Slap Bass (v2) — édition", data);

    File file = new File(tempDir, "special.osmpsvoice");
    OsmpsVoiceFile.save(voice, file);

    FmVoice loaded = OsmpsVoiceFile.load(file);
    assertEquals("Slap Bass (v2) — édition", loaded.getName());
}
```

**Step 2: Run tests to verify they pass** (implementation already handles these cases)

Run: `mvn test -pl app -Dtest=TestOsmpsVoiceFile`
Expected: PASS (all 6 tests)

**Step 3: Commit**

```bash
git add app/src/test/java/com/opensmpsdeck/io/TestOsmpsVoiceFile.java
git commit -m "test: add OsmpsVoiceFile error handling and edge case tests"
```

---

### Task 3: FmVoiceEditor — Save/Load Preset buttons

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/FmVoiceEditor.java:156-219` (buildButtonBar method)

**Step 1: Add Save Preset and Load Preset buttons to the button bar**

In `FmVoiceEditor.java`, modify `buildButtonBar()` to add two new buttons after the Preview button.

The "Save Preset" button:
1. Opens a `FileChooser` with `.osmpsvoice` filter in save mode
2. Calls `OsmpsVoiceFile.save(voice, file)` with the current editor voice
3. Shows an error alert on `IOException`

The "Load Preset" button:
1. Opens a `FileChooser` with `.osmpsvoice` filter in open mode
2. Calls `OsmpsVoiceFile.load(file)` to get an `FmVoice`
3. Copies all parameters from the loaded voice into the current editor voice (same pattern as the existing Paste button logic on lines 167-189)
4. Calls `drawAlgorithmDiagram()` and `updateOperatorBorders()` to refresh the UI
5. Shows an error alert on `IOException`

Add this import at the top of `FmVoiceEditor.java`:
```java
import com.opensmpsdeck.io.OsmpsVoiceFile;
import javafx.stage.FileChooser;
```

Add these two buttons in `buildButtonBar()`, after `previewBtn`:

```java
Button savePresetBtn = new Button("Save Preset");
savePresetBtn.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: #cccccc;");
savePresetBtn.setOnAction(e -> {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Save Voice Preset");
    chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("OpenSMPS Voice Preset", "*.osmpsvoice"));
    File file = chooser.showSaveDialog(getOwner());
    if (file != null) {
        try {
            OsmpsVoiceFile.save(voice, file);
        } catch (java.io.IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Failed to save preset: " + ex.getMessage(),
                    ButtonType.OK).showAndWait();
        }
    }
});

Button loadPresetBtn = new Button("Load Preset");
loadPresetBtn.setStyle("-fx-background-color: #2a2a2a; -fx-text-fill: #cccccc;");
loadPresetBtn.setOnAction(e -> {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Load Voice Preset");
    chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("OpenSMPS Voice Preset", "*.osmpsvoice"));
    File file = chooser.showOpenDialog(getOwner());
    if (file != null) {
        try {
            FmVoice loaded = OsmpsVoiceFile.load(file);
            applyVoiceData(loaded);
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Failed to load preset: " + ex.getMessage(),
                    ButtonType.OK).showAndWait();
        }
    }
});
```

Update the button bar children line:
```java
bar.getChildren().addAll(copyBtn, pasteBtn, initBtn, previewBtn, savePresetBtn, loadPresetBtn);
```

**Step 2: Extract `applyVoiceData` helper to avoid duplicating Paste logic**

The existing Paste handler (lines 167-189) copies all parameters from one voice to another. Extract this into a private method so both Paste and Load Preset can share it.

Add to `FmVoiceEditor`:
```java
/**
 * Copies all parameters from the source voice into the editor's working voice
 * and refreshes the algorithm diagram and operator borders.
 */
private void applyVoiceData(FmVoice source) {
    for (int op = 0; op < 4; op++) {
        voice.setMul(op, source.getMul(op));
        voice.setDt(op, source.getDt(op));
        voice.setTl(op, source.getTl(op));
        voice.setAr(op, source.getAr(op));
        voice.setD1r(op, source.getD1r(op));
        voice.setD2r(op, source.getD2r(op));
        voice.setD1l(op, source.getD1l(op));
        voice.setRr(op, source.getRr(op));
        voice.setRs(op, source.getRs(op));
        voice.setAm(op, source.getAm(op));
    }
    voice.setAlgorithm(source.getAlgorithm());
    voice.setFeedback(source.getFeedback());
    drawAlgorithmDiagram();
    updateOperatorBorders();
}
```

Refactor the Paste button handler to call `applyVoiceData(pasted)` instead of inline code.

**Step 3: Run tests to verify nothing broke**

Run: `mvn test -pl app`
Expected: PASS (all existing tests + new OsmpsVoiceFile tests)

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/FmVoiceEditor.java
git commit -m "feat: add Save/Load Preset buttons to FmVoiceEditor"
```

---

### Task 4: Import voice bank — add `.osmpsvoice` filter

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindowFileActions.java:259-315` (onImportVoiceBank method)

**Step 1: Add `.osmpsvoice` to the file chooser filter and handle it**

In `MainWindowFileActions.onImportVoiceBank()`, the file chooser currently accepts `*.ovm` and `*.rym2612`. Add `*.osmpsvoice` to the "Voice Files" filter.

Change the extension filter (line 266):
```java
new FileChooser.ExtensionFilter("Voice Files", "*.ovm", "*.rym2612", "*.osmpsvoice"),
```

Add a new filter entry:
```java
new FileChooser.ExtensionFilter("OpenSMPS Voice Preset", "*.osmpsvoice"),
```

Add handling before the `.rym2612` check (line 276). When the file ends with `.osmpsvoice`, load it directly with `OsmpsVoiceFile.load(file)` and add to the voice bank:

```java
if (file.getName().toLowerCase().endsWith(".osmpsvoice")) {
    FmVoice voice = OsmpsVoiceFile.load(file);
    song.getVoiceBank().add(voice);
    changed = true;
} else if (file.getName().toLowerCase().endsWith(".rym2612")) {
```

Add import at the top:
```java
import com.opensmpsdeck.io.OsmpsVoiceFile;
```

**Step 2: Run tests to verify nothing broke**

Run: `mvn test -pl app`
Expected: PASS

**Step 3: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/MainWindowFileActions.java
git commit -m "feat: add .osmpsvoice to voice bank import file filter"
```

---

### Task 5: InstrumentPanel — Export Preset button

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/InstrumentPanel.java:93-100` (voice button bar)

**Step 1: Add an "Export" button to the voice bank button bar**

In `InstrumentPanel`, add an "Export" button to the voice buttons bar that exports the selected voice as `.osmpsvoice`.

After line 96 (the "Del" button), add:

```java
createButton("Export", e -> exportSelectedVoiceAsPreset())
```

Add the method:
```java
private void exportSelectedVoiceAsPreset() {
    int index = getCurrentVoiceIndex();
    if (index < 0) return;

    FmVoice voice = song.getVoiceBank().get(index);
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Export Voice Preset");
    chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("OpenSMPS Voice Preset", "*.osmpsvoice"));
    chooser.setInitialFileName(voice.getName() + ".osmpsvoice");
    File file = chooser.showSaveDialog(getScene().getWindow());
    if (file != null) {
        try {
            OsmpsVoiceFile.save(voice, file);
        } catch (IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Failed to export voice preset: " + ex.getMessage(), ButtonType.OK);
            alert.setTitle("Export Error");
            alert.showAndWait();
        }
    }
}
```

Add import:
```java
import com.opensmpsdeck.io.OsmpsVoiceFile;
```

**Step 2: Run tests to verify nothing broke**

Run: `mvn test -pl app`
Expected: PASS

**Step 3: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/InstrumentPanel.java
git commit -m "feat: add Export Preset button to InstrumentPanel voice bank"
```

---

## Track B: ADSR Envelope Preview Curves

### Task 6: AdsrEnvelopeCalculator — core envelope math test

**Files:**
- Create: `app/src/test/java/com/opensmpsdeck/audio/TestAdsrEnvelopeCalculator.java`
- Create: `app/src/main/java/com/opensmpsdeck/audio/AdsrEnvelopeCalculator.java`

**Context:** The YM2612 ADSR envelope has 4 phases. We compute a normalized curve as `List<double[]>` where each element is `{normalizedTime, normalizedLevel}`. Time runs 0.0 to 1.0, level runs 0.0 (silence / -96 dB) to 1.0 (full volume / 0 dB). A key-off fraction (0.0-1.0) determines where the release phase begins on the time axis.

Rate parameters (AR, D1R, D2R, RR) control speed: 0 = stopped, higher = faster. D1L (0-15) controls the Decay 1 target level: 0 = 0 dB (no decay), 15 = -93 dB (near silence).

**Step 1: Write the failing test**

```java
package com.opensmpsdeck.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestAdsrEnvelopeCalculator {

    @Test
    void instantAttackMaxSustain() {
        // AR=31 (instant), D1R=0 (no decay), D2R=0, D1L=0 (0 dB), RR=15
        List<double[]> points = AdsrEnvelopeCalculator.compute(31, 0, 0, 0, 15, 0.7);

        assertFalse(points.isEmpty());

        // First point should be at time 0, level 0 (silence before attack)
        assertEquals(0.0, points.get(0)[0], 0.001);
        assertEquals(0.0, points.get(0)[1], 0.001);

        // After instant attack, level should reach 1.0 very quickly
        // Find the point just after attack completes
        double levelAtSustain = findLevelAt(points, 0.1);
        assertEquals(1.0, levelAtSustain, 0.05, "Should sustain at full volume");

        // At key-off boundary (0.7), level should still be ~1.0
        double levelAtKeyOff = findLevelAt(points, 0.69);
        assertEquals(1.0, levelAtKeyOff, 0.05);

        // After release, level should decay toward 0
        double levelAtEnd = findLevelAt(points, 0.95);
        assertTrue(levelAtEnd < 0.5, "Should be decaying after key-off");

        // Last point should be at time 1.0
        assertEquals(1.0, points.get(points.size() - 1)[0], 0.001);
    }

    @Test
    void slowAttackReachesFullVolume() {
        // AR=5 (slow), D1R=0, D2R=0, D1L=0, RR=15
        List<double[]> points = AdsrEnvelopeCalculator.compute(5, 0, 0, 0, 15, 0.7);

        // Level at 10% should be less than full (still attacking)
        double earlyLevel = findLevelAt(points, 0.05);
        assertTrue(earlyLevel < 0.8, "Slow attack should not reach full volume immediately");

        // But by sustain region it should be at full volume
        double lateLevel = findLevelAt(points, 0.5);
        assertEquals(1.0, lateLevel, 0.1);
    }

    @Test
    void decayToD1LLevel() {
        // AR=31 (instant), D1R=15 (fast decay), D2R=0, D1L=8 (~-24 dB), RR=15
        List<double[]> points = AdsrEnvelopeCalculator.compute(31, 15, 0, 8, 15, 0.7);

        // After attack + decay1, level should settle near D1L
        double d1lNormalized = AdsrEnvelopeCalculator.d1lToNormalized(8);
        double sustainLevel = findLevelAt(points, 0.5);
        assertEquals(d1lNormalized, sustainLevel, 0.15,
                "Level should be near D1L after fast decay");
    }

    @Test
    void zeroAttackRateProducesNoSound() {
        // AR=0 means infinite attack — never reaches full volume
        List<double[]> points = AdsrEnvelopeCalculator.compute(0, 0, 0, 0, 15, 0.7);

        // All levels should be 0 (never attacks)
        for (double[] point : points) {
            assertEquals(0.0, point[1], 0.001, "AR=0 should produce silence");
        }
    }

    @Test
    void d1lMappingBoundaries() {
        // D1L=0 -> 0 dB attenuation -> normalized 1.0
        assertEquals(1.0, AdsrEnvelopeCalculator.d1lToNormalized(0), 0.001);
        // D1L=15 -> -93 dB -> near 0
        assertTrue(AdsrEnvelopeCalculator.d1lToNormalized(15) < 0.05);
        // D1L=1 -> -3 dB
        double d1l1 = AdsrEnvelopeCalculator.d1lToNormalized(1);
        assertTrue(d1l1 > 0.6 && d1l1 < 0.8, "D1L=1 (-3 dB) should be ~0.71");
    }

    /** Finds the interpolated level at a given normalized time. */
    private double findLevelAt(List<double[]> points, double time) {
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i)[0] >= time) {
                double t0 = points.get(i - 1)[0];
                double t1 = points.get(i)[0];
                double l0 = points.get(i - 1)[1];
                double l1 = points.get(i)[1];
                double frac = (t1 > t0) ? (time - t0) / (t1 - t0) : 0;
                return l0 + frac * (l1 - l0);
            }
        }
        return points.get(points.size() - 1)[1];
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestAdsrEnvelopeCalculator`
Expected: FAIL — `AdsrEnvelopeCalculator` does not exist.

**Step 3: Write the implementation**

```java
package com.opensmpsdeck.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes normalized ADSR envelope curves from YM2612 operator parameters.
 *
 * <p>Produces a list of (time, level) points suitable for plotting on a Canvas.
 * Time is normalized to [0, 1]. Level is normalized to [0, 1] where 0 = silence
 * (-96 dB) and 1 = full volume (0 dB).
 *
 * <p>No JavaFX dependency — this class is fully unit-testable.
 */
public final class AdsrEnvelopeCalculator {

    /** Number of sample points to generate for the envelope curve. */
    private static final int RESOLUTION = 128;

    private AdsrEnvelopeCalculator() {}

    /**
     * Computes the ADSR envelope curve for one operator.
     *
     * @param ar          attack rate (0-31, 0 = no attack, 31 = instant)
     * @param d1r         decay 1 rate (0-31, 0 = no decay)
     * @param d2r         decay 2 rate (0-31, 0 = sustain forever)
     * @param d1l         decay 1 level (0-15, 0 = 0 dB, 15 = -93 dB)
     * @param rr          release rate (0-15, higher = faster)
     * @param keyOffFrac  normalized time at which key-off occurs (0.0-1.0)
     * @return list of {normalizedTime, normalizedLevel} points
     */
    public static List<double[]> compute(int ar, int d1r, int d2r, int d1l, int rr,
                                         double keyOffFrac) {
        List<double[]> points = new ArrayList<>(RESOLUTION + 2);

        double d1lLevel = d1lToNormalized(d1l);

        // Convert rates to durations as fractions of the pre-key-off time.
        // Higher rate = shorter duration.  Rate 0 = infinite (phase never completes).
        double attackDur = rateToDuration(ar, 31);
        double decay1Dur = (d1r == 0) ? Double.MAX_VALUE : rateToDuration(d1r, 31);
        double releaseDur = rateToDuration(rr * 2, 30); // RR is 0-15, scale to comparable range

        // Phase boundaries (as fractions of keyOffFrac)
        double phaseTime = 0;

        // Pre-compute: distribute the key-off time among attack, decay1, decay2
        double attackEnd = Math.min(attackDur, keyOffFrac);
        double decay1End = attackEnd + Math.min(decay1Dur, keyOffFrac - attackEnd);
        // decay2 fills the rest until key-off

        double level = 0.0; // Start at silence

        for (int i = 0; i <= RESOLUTION; i++) {
            double t = (double) i / RESOLUTION;
            double newLevel;

            if (ar == 0) {
                // AR=0: never attacks
                newLevel = 0.0;
            } else if (t < attackEnd) {
                // Attack phase: exponential rise from 0 to 1
                double phase = t / attackEnd;
                newLevel = 1.0 - Math.pow(1.0 - phase, 2.0);
            } else if (t < keyOffFrac) {
                // Decay 1: fall from 1.0 toward d1lLevel
                if (d1r == 0) {
                    newLevel = 1.0; // No decay
                } else if (t < decay1End) {
                    double phase = (t - attackEnd) / (decay1End - attackEnd);
                    newLevel = 1.0 + (d1lLevel - 1.0) * phase;
                } else {
                    // Decay 2: fall from d1lLevel toward 0
                    if (d2r == 0) {
                        newLevel = d1lLevel; // Sustain at D1L
                    } else {
                        double d2Duration = rateToDuration(d2r, 31);
                        double d2Elapsed = t - decay1End;
                        double phase = Math.min(d2Elapsed / d2Duration, 1.0);
                        newLevel = d1lLevel * (1.0 - phase);
                    }
                }
            } else {
                // Release phase: fall from current level toward 0
                double levelAtKeyOff;
                if (ar == 0) {
                    levelAtKeyOff = 0.0;
                } else if (d1r == 0) {
                    levelAtKeyOff = 1.0;
                } else if (d2r == 0) {
                    levelAtKeyOff = d1lLevel;
                } else {
                    // Estimate level at key-off from decay2
                    double d2Duration = rateToDuration(d2r, 31);
                    double d2Elapsed = keyOffFrac - decay1End;
                    double phase = Math.min(d2Elapsed / d2Duration, 1.0);
                    levelAtKeyOff = d1lLevel * (1.0 - phase);
                }

                double releaseTime = t - keyOffFrac;
                double releaseTotal = 1.0 - keyOffFrac;
                if (releaseTotal <= 0 || releaseDur <= 0) {
                    newLevel = 0.0;
                } else {
                    double phase = Math.min(releaseTime / Math.min(releaseDur, releaseTotal), 1.0);
                    newLevel = levelAtKeyOff * (1.0 - phase);
                }
            }

            level = Math.max(0.0, Math.min(1.0, newLevel));
            points.add(new double[]{t, level});
        }

        return points;
    }

    /**
     * Converts D1L (0-15) to a normalized level (0.0-1.0).
     *
     * <p>D1L=0 means 0 dB attenuation (full volume, returns 1.0).
     * D1L=1-14 map to -3 dB to -42 dB in 3 dB steps.
     * D1L=15 maps to -93 dB (near silence).
     */
    public static double d1lToNormalized(int d1l) {
        if (d1l == 0) return 1.0;
        if (d1l == 15) return Math.pow(10.0, -93.0 / 20.0); // ~0.022
        double db = -3.0 * d1l;
        return Math.pow(10.0, db / 20.0);
    }

    /**
     * Converts a rate parameter to a normalized duration.
     * Higher rate = shorter duration. Rate 0 = infinite.
     */
    private static double rateToDuration(int rate, int maxRate) {
        if (rate <= 0) return Double.MAX_VALUE;
        // Exponential mapping: rate=maxRate -> very short, rate=1 -> long
        return 0.8 * Math.pow(0.85, rate - 1);
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -pl app -Dtest=TestAdsrEnvelopeCalculator`
Expected: PASS (all 5 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/AdsrEnvelopeCalculator.java \
       app/src/test/java/com/opensmpsdeck/audio/TestAdsrEnvelopeCalculator.java
git commit -m "feat: add AdsrEnvelopeCalculator for FM operator envelope curves"
```

---

### Task 7: FmVoiceEditor — envelope preview Canvas

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/FmVoiceEditor.java`

**Step 1: Add the envelope Canvas to the layout**

In `FmVoiceEditor`, add a new Canvas between the button bar and the operator scroll pane.

Add import:
```java
import com.opensmpsdeck.audio.AdsrEnvelopeCalculator;
```

Add field:
```java
private static final double ENVELOPE_CANVAS_HEIGHT = 100;
private final Canvas envelopeCanvas;
```

In the constructor, after `algorithmCanvas` creation (~line 71), create the envelope canvas:
```java
envelopeCanvas = new Canvas(680, ENVELOPE_CANVAS_HEIGHT);
StackPane envelopeWrapper = new StackPane(envelopeCanvas);
envelopeWrapper.setStyle("-fx-background-color: " + PANEL_COLOR + "; "
        + "-fx-border-color: #333333; -fx-border-width: 1;");
envelopeWrapper.setPadding(new Insets(4));
```

Modify the `mainLayout.getChildren().addAll(...)` call (line 96) to insert the envelope wrapper:
```java
mainLayout.getChildren().addAll(topRow, canvasWrapper, buttonBar, envelopeWrapper, scrollPane);
```

Increase the dialog height to accommodate the new canvas. Change line 98:
```java
dialogPane.setPrefSize(720, 740);
```

Add the `redrawEnvelopePreview()` method to `FmVoiceEditor`:
```java
/**
 * Redraws all 4 operator ADSR envelopes on the envelope canvas.
 * Carriers are drawn in cyan, modulators in gray, both at 50% opacity.
 */
private void redrawEnvelopePreview() {
    GraphicsContext gc = envelopeCanvas.getGraphicsContext2D();
    double w = envelopeCanvas.getWidth();
    double h = envelopeCanvas.getHeight();

    // Clear background
    gc.setFill(Color.web(PANEL_COLOR));
    gc.fillRect(0, 0, w, h);

    double keyOffFrac = 0.7;

    // Draw key-off marker
    double keyOffX = keyOffFrac * w;
    gc.setStroke(Color.web("#555555"));
    gc.setLineDashes(4, 4);
    gc.setLineWidth(1);
    gc.strokeLine(keyOffX, 0, keyOffX, h);
    gc.setLineDashes((double[]) null);

    // Draw "Key Off" label
    gc.setFill(Color.web("#555555"));
    gc.setFont(Font.font("System", 10));
    gc.fillText("Key Off", keyOffX + 3, 12);

    // Draw grid lines at 0 dB and -48 dB
    gc.setStroke(Color.web("#333333"));
    gc.setLineWidth(0.5);
    gc.strokeLine(0, 2, w, 2);           // 0 dB (top)
    gc.strokeLine(0, h / 2, w, h / 2);  // ~-48 dB (middle)
    gc.strokeLine(0, h - 2, w, h - 2);  // -96 dB (bottom)

    // Draw each operator envelope
    gc.setLineWidth(1.5);
    for (int display = 0; display < 4; display++) {
        int smpsOp = FmVoice.displayToSmps(display);
        boolean carrier = voice.isCarrier(smpsOp);

        int ar = voice.getAr(smpsOp);
        int d1r = voice.getD1r(smpsOp);
        int d2r = voice.getD2r(smpsOp);
        int d1l = voice.getD1l(smpsOp);
        int rr = voice.getRr(smpsOp);

        java.util.List<double[]> points = AdsrEnvelopeCalculator.compute(
                ar, d1r, d2r, d1l, rr, keyOffFrac);

        Color lineColor = carrier
                ? Color.web(CARRIER_COLOR, 0.5)
                : Color.web(MODULATOR_COLOR, 0.5);
        gc.setStroke(lineColor);

        gc.beginPath();
        for (int i = 0; i < points.size(); i++) {
            double px = points.get(i)[0] * w;
            double py = h - points.get(i)[1] * (h - 4) - 2; // Invert Y, 2px margin
            if (i == 0) {
                gc.moveTo(px, py);
            } else {
                gc.lineTo(px, py);
            }
        }
        gc.stroke();
    }
}
```

Call `redrawEnvelopePreview()` at the end of the constructor (after `drawAlgorithmDiagram()` and `updateOperatorBorders()`):
```java
redrawEnvelopePreview();
```

**Step 2: Wire live updates from slider changes**

In `buildSliderRow()`, after the existing `onChange.accept(intVal)` call (line 331), add a call to redraw:
```java
slider.valueProperty().addListener((obs, oldVal, newVal) -> {
    int intVal = newVal.intValue();
    valueLabel.setText(String.valueOf(intVal));
    onChange.accept(intVal);
    redrawEnvelopePreview();
});
```

Also add `redrawEnvelopePreview()` calls in:
- The algorithm combo listener (after `updateOperatorBorders()`)
- The `applyVoiceData()` method (after `updateOperatorBorders()`)
- The Init button handler (after `updateOperatorBorders()`)

**Step 3: Run tests to verify nothing broke**

Run: `mvn test -pl app`
Expected: PASS

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/FmVoiceEditor.java
git commit -m "feat: add ADSR envelope preview Canvas to FmVoiceEditor"
```

---

### Task 8: Run full test suite and verify

**Step 1: Run all tests**

Run: `mvn test`
Expected: PASS (all tests including new TestOsmpsVoiceFile and TestAdsrEnvelopeCalculator)

**Step 2: Commit any remaining changes**

If all tests pass and there are no uncommitted changes, this task is done.

```bash
git status
```
