# Phase 8: Polish Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add per-channel solo/mute toggles and WAV export to OpenSMPS Deck.

**Architecture:** Solo/Mute wires TrackerGrid header clicks to existing chip mute APIs. WAV Export renders offline via SmpsDriver.read() loop and writes RIFF/WAV.

**Tech Stack:** JavaFX Canvas, SmpsDriver, PatternCompiler, java.io

---

### Task 1: Per-Channel Solo/Mute State and Click Handling

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java`

**Step 1: Add mute/solo state fields**

Add to TrackerGrid class fields:

```java
private final boolean[] channelMuted = new boolean[Pattern.CHANNEL_COUNT];
private int soloChannel = -1;
private PlaybackEngine playbackEngine;

public void setPlaybackEngine(PlaybackEngine engine) {
    this.playbackEngine = engine;
}
```

**Step 2: Add click detection in mouse handler**

In the canvas mouse click handler, detect clicks on channel headers (y < HEADER_HEIGHT). Determine which channel was clicked by `(mouseX - ROW_NUM_WIDTH) / CHANNEL_WIDTH`. On regular click: toggle mute. On Ctrl+click: toggle solo.

**Step 3: Implement applyMuteState() helper**

```java
private void applyMuteState() {
    if (playbackEngine == null) return;
    for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
        boolean muted = soloChannel >= 0 ? (ch != soloChannel) : channelMuted[ch];
        if (ch < 6) {
            playbackEngine.setFmMute(ch, muted);
        } else {
            playbackEngine.setPsgMute(ch - 6, muted);
        }
    }
}
```

**Step 4: Implement toggleMute() and toggleSolo()**

```java
private void toggleMute(int channel) {
    if (soloChannel >= 0) {
        soloChannel = -1; // exit solo mode first
    }
    channelMuted[channel] = !channelMuted[channel];
    applyMuteState();
    redraw();
}

private void toggleSolo(int channel) {
    if (soloChannel == channel) {
        soloChannel = -1; // unsolo
    } else {
        soloChannel = channel;
    }
    applyMuteState();
    redraw();
}
```

**Step 5: Update drawHeader() for mute/solo visual feedback**

In the header drawing loop, change fill color based on state:
- `soloChannel == ch`: gold `#ffcc00`
- `channelMuted[ch]` or `(soloChannel >= 0 && soloChannel != ch)`: grey `#555555`
- Normal: `#88aacc`

Draw strikethrough line for muted channels.

**Step 6: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java
git commit -m "feat: add per-channel solo/mute toggles to TrackerGrid"
```

---

### Task 2: Wire PlaybackEngine to TrackerGrid from MainWindow

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/ui/MainWindow.java`

**Step 1: Pass PlaybackEngine to TrackerGrid in createSongTabUI()**

After `songTab.buildContent()`, add:

```java
songTab.getTrackerGrid().setPlaybackEngine(playbackEngine);
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/ui/MainWindow.java
git commit -m "feat: wire PlaybackEngine to TrackerGrid for mute control"
```

---

### Task 3: WavExporter Implementation

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/io/WavExporter.java`

**Step 1: Write WavExporter class**

```java
package com.opensmps.deck.io;

import com.opensmps.deck.audio.PlaybackEngine;
import com.opensmps.deck.model.Song;

import java.io.*;

public class WavExporter {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int MAX_DURATION_SECONDS = 600; // 10 minutes
    private static final int BUFFER_FRAMES = 1024;

    private int loopCount = 2;

    public void setLoopCount(int loopCount) {
        this.loopCount = Math.max(1, loopCount);
    }

    public void export(Song song, File outputFile) throws IOException {
        PlaybackEngine engine = new PlaybackEngine();

        // Collect all PCM data
        ByteArrayOutputStream pcmData = new ByteArrayOutputStream();
        int maxFrames = SAMPLE_RATE * MAX_DURATION_SECONDS;
        int totalFrames = 0;
        int fadeStartFrame = -1;
        int fadeLengthFrames = 0;

        for (int loop = 0; loop < loopCount; loop++) {
            engine.loadSong(song);
            short[] buffer = new short[BUFFER_FRAMES * CHANNELS];
            boolean lastLoop = (loop == loopCount - 1);
            int loopStartFrame = totalFrames;

            while (totalFrames < maxFrames) {
                engine.renderBuffer(buffer);
                if (engine.getDriver().isComplete()) break;

                for (short sample : buffer) {
                    pcmData.write(sample & 0xFF);
                    pcmData.write((sample >> 8) & 0xFF);
                }
                totalFrames += BUFFER_FRAMES;
            }

            if (lastLoop && loopCount > 1) {
                fadeStartFrame = loopStartFrame;
                fadeLengthFrames = totalFrames - loopStartFrame;
            }
        }

        byte[] pcm = pcmData.toByteArray();

        // Apply fade-out on final loop
        if (fadeStartFrame >= 0 && fadeLengthFrames > 0) {
            applyFadeOut(pcm, fadeStartFrame, fadeLengthFrames);
        }

        // Write WAV file
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile)))) {
            writeWavHeader(dos, pcm.length);
            dos.write(pcm);
        }
    }

    private void applyFadeOut(byte[] pcm, int fadeStartFrame, int fadeLengthFrames) {
        int startByte = fadeStartFrame * CHANNELS * (BITS_PER_SAMPLE / 8);
        int totalFadeSamples = fadeLengthFrames * CHANNELS;
        for (int i = 0; i < totalFadeSamples && (startByte + i * 2 + 1) < pcm.length; i++) {
            int bytePos = startByte + i * 2;
            short sample = (short) ((pcm[bytePos] & 0xFF) | (pcm[bytePos + 1] << 8));
            float progress = (float) (i / CHANNELS) / fadeLengthFrames;
            float gain = 1.0f - progress;
            sample = (short) (sample * gain);
            pcm[bytePos] = (byte) (sample & 0xFF);
            pcm[bytePos + 1] = (byte) ((sample >> 8) & 0xFF);
        }
    }

    private void writeWavHeader(DataOutputStream dos, int dataSize) throws IOException {
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;

        // RIFF header
        dos.writeBytes("RIFF");
        writeLittleEndianInt(dos, 36 + dataSize);
        dos.writeBytes("WAVE");

        // fmt chunk
        dos.writeBytes("fmt ");
        writeLittleEndianInt(dos, 16);              // chunk size
        writeLittleEndianShort(dos, (short) 1);     // PCM format
        writeLittleEndianShort(dos, (short) CHANNELS);
        writeLittleEndianInt(dos, SAMPLE_RATE);
        writeLittleEndianInt(dos, byteRate);
        writeLittleEndianShort(dos, (short) blockAlign);
        writeLittleEndianShort(dos, (short) BITS_PER_SAMPLE);

        // data chunk
        dos.writeBytes("data");
        writeLittleEndianInt(dos, dataSize);
    }

    private void writeLittleEndianInt(DataOutputStream dos, int value) throws IOException {
        dos.write(value & 0xFF);
        dos.write((value >> 8) & 0xFF);
        dos.write((value >> 16) & 0xFF);
        dos.write((value >> 24) & 0xFF);
    }

    private void writeLittleEndianShort(DataOutputStream dos, short value) throws IOException {
        dos.write(value & 0xFF);
        dos.write((value >> 8) & 0xFF);
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/io/WavExporter.java
git commit -m "feat: add WavExporter for offline PCM rendering to WAV"
```

---

### Task 4: WavExporter Test

**Files:**
- Create: `app/src/test/java/com/opensmps/deck/io/TestWavExporter.java`

**Step 1: Write test**

Test that exporting a minimal song produces a valid WAV file with correct header and non-zero audio data.

**Step 2: Commit**

```bash
git add app/src/test/java/com/opensmps/deck/io/TestWavExporter.java
git commit -m "test: add WavExporter tests for header and audio output"
```

---

### Task 5: WAV Export Menu Item in MainWindow

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/ui/MainWindow.java`

**Step 1: Add "Export WAV..." menu item after "Export SMPS..."**

```java
MenuItem exportWavItem = new MenuItem("Export WAV...");
exportWavItem.setOnAction(e -> onExportWav());
```

Add to fileMenu items list after exportItem.

**Step 2: Implement onExportWav()**

```java
private void onExportWav() {
    SongTab tab = getActiveSongTab();
    if (tab == null) return;
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Export WAV Audio");
    fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("WAV Audio", "*.wav"));
    File file = fileChooser.showSaveDialog(stage);
    if (file != null) {
        try {
            WavExporter exporter = new WavExporter();
            exporter.export(tab.getSong(), file);
        } catch (IOException ex) {
            showError("Failed to export WAV", ex.getMessage());
        }
    }
}
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/ui/MainWindow.java
git commit -m "feat: add Export WAV menu item to MainWindow"
```

---

### Task 6: Build and verify all tests pass

**Step 1: Run full test suite**

```bash
mvn test
```

Expected: All tests pass (148+ existing + new WavExporter tests).

**Step 2: Final commit if any fixups needed**
