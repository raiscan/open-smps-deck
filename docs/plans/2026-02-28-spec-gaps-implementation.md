# Spec Gap Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Close four spec gaps: playback position API, live reload on edit, playback cursor sync, mute reset on song load, and configurable WAV export with extend/inset fade.

**Architecture:** Expose sequencer byte position through SmpsSequencer → SmpsDriver → PlaybackEngine, then wire coordinator + UI for live reload and cursor sync. Replace WavExporter hardcoded fade with seconds-based extend/inset model, surface in a dialog.

**Tech Stack:** Java 21, JUnit 5, JavaFX (UI only)

---

### Task 1: Add getTrackPosition() to SmpsSequencer

**Files:**
- Modify: `synth-core/src/main/java/com/opensmps/smps/SmpsSequencer.java`
- Test: `synth-core/src/test/java/com/opensmps/smps/TestSmpsSequencer.java`

**Context:** SmpsSequencer maintains a `List<Track>` where each Track has `public int pos` (current byte offset in the SMPS data). The `getTracks()` method exists but exposes mutable Track objects. We need a clean accessor to query position by channel type and hardware channel ID.

The channel type + channelId mapping to UI channels:
- FM1 = TrackType.FM, channelId=0
- FM2 = TrackType.FM, channelId=1
- DAC  = TrackType.DAC, channelId=5
- PSG1 = TrackType.PSG, channelId=0

**Step 1: Write the failing test**

Add to `TestSmpsSequencer.java` (find the existing test class):

```java
@Test
void getTrackPositionReturnsByteOffset() {
    // Build a minimal SMPS binary with known structure.
    // After the sequencer advances, Track.pos moves past the first note.
    SmpsSequencer seq = buildMinimalSequencer();

    // Before advancing, FM channel 0's track position should be at the track start
    int initialPos = seq.getTrackPosition(SmpsSequencer.TrackType.FM, 0);
    assertTrue(initialPos >= 0, "FM channel 0 should have a valid initial position");

    // Advance enough for the sequencer to process one note event
    seq.advance(1.0);
    seq.advance(1.0);

    int advancedPos = seq.getTrackPosition(SmpsSequencer.TrackType.FM, 0);
    assertTrue(advancedPos > initialPos,
            "After advancing, position should move past initial note bytes");
}

@Test
void getTrackPositionReturnsNegativeForMissingChannel() {
    SmpsSequencer seq = buildMinimalSequencer();
    assertEquals(-1, seq.getTrackPosition(SmpsSequencer.TrackType.PSG, 3),
            "Non-existent PSG3 track should return -1");
}
```

The `buildMinimalSequencer()` helper must construct a `StubSmpsData` with at least one FM track containing a note byte + duration, then create a `SmpsSequencer` configured for S2 mode. Reference existing helpers in the test file (look for patterns that build `StubSmpsData` and construct sequencers).

**Step 2: Run test to verify it fails**

Run: `mvn test -pl synth-core -Dtest=TestSmpsSequencer#getTrackPositionReturnsByteOffset -Dtest=TestSmpsSequencer#getTrackPositionReturnsNegativeForMissingChannel`
Expected: Compilation error — `getTrackPosition` method does not exist.

**Step 3: Write minimal implementation**

Add to `SmpsSequencer.java` (after the existing `getTracks()` method around line 613):

```java
/**
 * Returns the current byte offset for the track matching the given
 * channel type and hardware channel ID, or {@code -1} if no such
 * track exists.
 */
public int getTrackPosition(TrackType type, int channelId) {
    for (int i = 0; i < tracks.size(); i++) {
        Track t = tracks.get(i);
        if (t.type == type && t.channelId == channelId && t.active) {
            return t.pos;
        }
    }
    return -1;
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl synth-core -Dtest=TestSmpsSequencer`
Expected: PASS

**Step 5: Commit**

```bash
git add synth-core/src/main/java/com/opensmps/smps/SmpsSequencer.java synth-core/src/test/java/com/opensmps/smps/TestSmpsSequencer.java
git commit -m "feat: add getTrackPosition() to SmpsSequencer for playback position queries"
```

---

### Task 2: Route getTrackPosition() through SmpsDriver

**Files:**
- Modify: `synth-core/src/main/java/com/opensmps/driver/SmpsDriver.java`
- Test: `synth-core/src/test/java/com/opensmps/driver/TestSmpsDriver.java`

**Context:** SmpsDriver manages a list of sequencers. Music sequencers are non-SFX. We need to delegate position queries to the first active music sequencer.

**Step 1: Write the failing test**

Add to the existing `TestSmpsDriver.java` (or create if it doesn't exist):

```java
@Test
void getTrackPositionDelegatesToMusicSequencer() {
    SmpsDriver driver = new SmpsDriver(44100.0);
    // Build and add a music sequencer with FM channel 0
    SmpsSequencer seq = buildMinimalMusicSequencer(driver);
    driver.addSequencer(seq, false);

    int pos = driver.getTrackPosition(SmpsSequencer.TrackType.FM, 0);
    assertTrue(pos >= 0, "Should return position from music sequencer");
}

@Test
void getTrackPositionReturnsNegativeWhenNoSequencer() {
    SmpsDriver driver = new SmpsDriver(44100.0);
    assertEquals(-1, driver.getTrackPosition(SmpsSequencer.TrackType.FM, 0),
            "No sequencer loaded — should return -1");
}
```

Build `buildMinimalMusicSequencer(driver)` using the same pattern as Task 1's helper. The driver is passed as the Synthesizer argument to the SmpsSequencer constructor.

**Step 2: Run test to verify it fails**

Run: `mvn test -pl synth-core -Dtest=TestSmpsDriver#getTrackPositionDelegatesToMusicSequencer`
Expected: Compilation error — `getTrackPosition` method does not exist on SmpsDriver.

**Step 3: Write minimal implementation**

Add to `SmpsDriver.java` (after the `stopAllSfx()` method, around line 199):

```java
/**
 * Returns the byte position of the given channel in the first active
 * music (non-SFX) sequencer, or {@code -1} if unavailable.
 */
public int getTrackPosition(SmpsSequencer.TrackType type, int channelId) {
    synchronized (sequencersLock) {
        for (int i = 0; i < sequencers.size(); i++) {
            SmpsSequencer seq = sequencers.get(i);
            if (!isSfx(seq)) {
                return seq.getTrackPosition(type, channelId);
            }
        }
    }
    return -1;
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl synth-core -Dtest=TestSmpsDriver`
Expected: PASS

**Step 5: Commit**

```bash
git add synth-core/src/main/java/com/opensmps/driver/SmpsDriver.java synth-core/src/test/java/com/opensmps/driver/TestSmpsDriver.java
git commit -m "feat: route getTrackPosition() through SmpsDriver to music sequencer"
```

---

### Task 3: Store CompilationResult in PlaybackEngine + getPlaybackPosition()

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java`
- Modify: `app/src/test/java/com/opensmpsdeck/audio/TestPlaybackEngine.java`

**Context:** `PlaybackEngine.loadSong()` currently calls `compiler.compile(song)` which internally calls `compileDetailed().getSmpsDataUnsafe()`, discarding the `CompilationResult` with its `ChannelTimeline` metadata. We need to store the result and expose a position query.

The position query uses FM1 (UI channel 0 = TrackType.FM, channelId=0) as the reference channel because it's present in virtually every song.

**Step 1: Write the failing test**

Add to `TestPlaybackEngine.java`:

```java
@Test
void getPlaybackPositionReturnsValidPositionAfterLoad() {
    Song song = createTestSong();
    PlaybackEngine engine = new PlaybackEngine();
    engine.loadSong(song);

    // Render some audio so the sequencer advances
    for (int i = 0; i < 5; i++) {
        engine.renderBuffer(new short[2048]);
    }

    PlaybackEngine.PlaybackPosition pos = engine.getPlaybackPosition();
    assertNotNull(pos, "Should return a position after loading a song with FM data");
    assertTrue(pos.orderIndex() >= 0, "Order index should be non-negative");
    assertTrue(pos.rowIndex() >= 0, "Row index should be non-negative");
}

@Test
void getPlaybackPositionReturnsNullBeforeLoad() {
    PlaybackEngine engine = new PlaybackEngine();
    assertNull(engine.getPlaybackPosition(),
            "Should return null when no song is loaded");
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestPlaybackEngine#getPlaybackPositionReturnsValidPositionAfterLoad`
Expected: Compilation error — `PlaybackPosition` and `getPlaybackPosition()` don't exist.

**Step 3: Write minimal implementation**

Add the record and fields to `PlaybackEngine.java`:

```java
// --- Add record inside the class, before the constructor ---
/** Resolved playback position in terms of the original song's order/row. */
public record PlaybackPosition(int orderIndex, int rowIndex) {}

// --- Add fields ---
private PatternCompiler.CompilationResult compilationResult;
private int baseOrderIndex;
```

Modify `loadSong()` to store the compilation result:

```java
public void loadSong(Song song) {
    driver.stopAll();
    baseOrderIndex = 0;

    PatternCompiler.CompilationResult result = compiler.compileDetailed(song);
    this.compilationResult = result;
    byte[] smps = result.getSmpsDataUnsafe();

    // ... rest of existing loadSong code unchanged (baseNoteOffset, SimpleSmpsData, etc.)
}
```

Modify `playFromPosition()` to set baseOrderIndex:

```java
public void playFromPosition(Song song, int orderIndex, int rowIndex) {
    baseOrderIndex = orderIndex;
    Song slice = createPlaybackSlice(song, orderIndex, rowIndex);
    // Inline the loadSong logic without resetting baseOrderIndex:
    driver.stopAll();

    PatternCompiler.CompilationResult result = compiler.compileDetailed(slice);
    this.compilationResult = result;
    byte[] smps = result.getSmpsDataUnsafe();

    int baseNoteOffset = switch (slice.getSmpsMode()) {
        case S1 -> 0;
        case S2 -> 1;
        case S3K -> 0;
    };
    SimpleSmpsData data = new SimpleSmpsData(smps, baseNoteOffset);

    if (!slice.getPsgEnvelopes().isEmpty()) {
        byte[][] envs = new byte[slice.getPsgEnvelopes().size()][];
        for (int i = 0; i < envs.length; i++) {
            envs[i] = slice.getPsgEnvelopes().get(i).getData();
        }
        data.setPsgEnvelopes(envs);
    }

    DacData dacData = null;
    if (!slice.getDacSamples().isEmpty()) {
        Map<Integer, byte[]> sampleBank = new HashMap<>();
        Map<Integer, DacData.DacEntry> mapping = new HashMap<>();
        for (int i = 0; i < slice.getDacSamples().size(); i++) {
            DacSample dac = slice.getDacSamples().get(i);
            int sampleId = i;
            sampleBank.put(sampleId, dac.getDataDirect());
            mapping.put(0x81 + i, new DacData.DacEntry(sampleId, dac.getRate()));
        }
        int baseCycles = switch (slice.getSmpsMode()) {
            case S1 -> 301;
            case S2 -> 288;
            case S3K -> 297;
        };
        dacData = new DacData(sampleBank, mapping, baseCycles);
    }

    SmpsSequencerConfig config = buildConfig(slice.getSmpsMode());
    currentSequencer = new SmpsSequencer(data, dacData, driver, config);
    driver.addSequencer(currentSequencer, false);

    if (audioOutput == null) {
        audioOutput = new AudioOutput(driver);
    }
    audioOutput.start();
}
```

Note: This duplicates the loadSong body because playFromPosition needs to set baseOrderIndex before the load without having it overwritten. Extract the shared logic into a private helper:

```java
private void loadSongInternal(Song song) {
    driver.stopAll();

    PatternCompiler.CompilationResult result = compiler.compileDetailed(song);
    this.compilationResult = result;
    byte[] smps = result.getSmpsDataUnsafe();

    int baseNoteOffset = switch (song.getSmpsMode()) {
        case S1 -> 0;
        case S2 -> 1;
        case S3K -> 0;
    };
    SimpleSmpsData data = new SimpleSmpsData(smps, baseNoteOffset);

    if (!song.getPsgEnvelopes().isEmpty()) {
        byte[][] envs = new byte[song.getPsgEnvelopes().size()][];
        for (int i = 0; i < envs.length; i++) {
            envs[i] = song.getPsgEnvelopes().get(i).getData();
        }
        data.setPsgEnvelopes(envs);
    }

    DacData dacData = null;
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
            case S1 -> 301;
            case S2 -> 288;
            case S3K -> 297;
        };
        dacData = new DacData(sampleBank, mapping, baseCycles);
    }

    SmpsSequencerConfig config = buildConfig(song.getSmpsMode());
    currentSequencer = new SmpsSequencer(data, dacData, driver, config);
    driver.addSequencer(currentSequencer, false);
}
```

Then:

```java
public void loadSong(Song song) {
    baseOrderIndex = 0;
    loadSongInternal(song);
}

public void playFromPosition(Song song, int orderIndex, int rowIndex) {
    baseOrderIndex = orderIndex;
    Song slice = createPlaybackSlice(song, orderIndex, rowIndex);
    loadSongInternal(slice);
    if (audioOutput == null) {
        audioOutput = new AudioOutput(driver);
    }
    audioOutput.start();
}
```

Add `getPlaybackPosition()`:

```java
import com.opensmps.smps.SmpsSequencer;

/**
 * Returns the current playback position in terms of the original song's
 * order list and pattern row, or {@code null} if no song is loaded.
 */
public PlaybackPosition getPlaybackPosition() {
    if (compilationResult == null) return null;
    int pos = driver.getTrackPosition(SmpsSequencer.TrackType.FM, 0);
    if (pos < 0) return null;
    PatternCompiler.CursorPosition cursor =
            compilationResult.resolveChannelPosition(0, pos);
    if (cursor == null) return null;
    return new PlaybackPosition(
            cursor.orderIndex() + baseOrderIndex,
            cursor.rowIndex());
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestPlaybackEngine`
Expected: All PASS (existing + new tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java app/src/test/java/com/opensmpsdeck/audio/TestPlaybackEngine.java
git commit -m "feat: store CompilationResult and expose getPlaybackPosition() in PlaybackEngine"
```

---

### Task 4: Position-preserving reload()

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java`
- Modify: `app/src/test/java/com/opensmpsdeck/audio/TestPlaybackEngine.java`

**Context:** `reload()` currently calls `loadSong()` which stops everything and restarts from order 0. The spec says reload should resume from the approximate playback position.

**Step 1: Write the failing test**

Add to `TestPlaybackEngine.java`:

```java
@Test
void reloadPreservesApproximatePlaybackPosition() {
    Song song = createTwoOrderSong();
    PlaybackEngine engine = new PlaybackEngine();
    engine.loadSong(song);

    // Render enough to get past order 0 into order 1
    // (the test song has 2 order rows with short patterns)
    for (int i = 0; i < 40; i++) {
        engine.renderBuffer(new short[4096]);
    }

    PlaybackEngine.PlaybackPosition beforeReload = engine.getPlaybackPosition();
    // Skip test if we can't get a position (driver completed)
    if (beforeReload == null) return;

    engine.reload(song);

    PlaybackEngine.PlaybackPosition afterReload = engine.getPlaybackPosition();
    assertNotNull(afterReload, "Should have a position after reload");
    // The order index should match (approximate — the row may differ slightly)
    assertEquals(beforeReload.orderIndex(), afterReload.orderIndex(),
            "Reload should restart at the same order row");
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestPlaybackEngine#reloadPreservesApproximatePlaybackPosition`
Expected: FAIL — after reload, position resets to order 0.

**Step 3: Write minimal implementation**

Update `reload()` in `PlaybackEngine.java`:

```java
/** Reload song: recompile and restart from current position if playing. */
public void reload(Song song) {
    PlaybackPosition pos = getPlaybackPosition();
    boolean wasPlaying = isPlaying();

    if (pos != null && wasPlaying) {
        playFromPosition(song, pos.orderIndex(), pos.rowIndex());
    } else {
        loadSong(song);
        if (wasPlaying) {
            play();
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestPlaybackEngine`
Expected: All PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java app/src/test/java/com/opensmpsdeck/audio/TestPlaybackEngine.java
git commit -m "feat: reload() preserves approximate playback position"
```

---

### Task 5: SongTabCoordinator.onSongEdited() with reload and mute snapshot

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/SongTabCoordinator.java`
- Modify: `app/src/test/java/com/opensmpsdeck/ui/TestSongTabCoordinator.java`

**Context:** `SongTabCoordinator` uses a `PlaybackGateway` interface for unit-testable coordination. We need to add `reload()` to the gateway and implement `onSongEdited()` that conditionally reloads when playing, with mute state snapshot/restore.

The mute state is tracked via `boolean[] channelMuted` and `int soloChannel`. Since TrackerGrid is a UI class, the coordinator needs an abstraction to read/write mute state.

**Step 1: Write the failing test**

Add to `TestSongTabCoordinator.java`:

```java
@Test
void onSongEditedReloadsWhenPlaying() {
    FakePlaybackGateway gateway = new FakePlaybackGateway();
    gateway.playing = true;
    SongTabCoordinator coordinator = new SongTabCoordinator(gateway);
    Song song = new Song();

    coordinator.onSongEdited(song);

    assertTrue(gateway.events.contains("reload"),
            "Should trigger reload when playing");
}

@Test
void onSongEditedDoesNothingWhenStopped() {
    FakePlaybackGateway gateway = new FakePlaybackGateway();
    gateway.playing = false;
    SongTabCoordinator coordinator = new SongTabCoordinator(gateway);

    coordinator.onSongEdited(new Song());

    assertEquals(List.of("isPlaying"), gateway.events,
            "When stopped, should only check isPlaying and do nothing else");
}
```

Also update `FakePlaybackGateway` to support `reload()`:

```java
// In FakePlaybackGateway, add:
private Song lastReloadedSong;

@Override
public void reload(Song song) {
    events.add("reload");
    lastReloadedSong = song;
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestSongTabCoordinator#onSongEditedReloadsWhenPlaying`
Expected: Compilation error — `onSongEdited()` and `reload()` don't exist.

**Step 3: Write minimal implementation**

Update `SongTabCoordinator.PlaybackGateway` interface:

```java
interface PlaybackGateway {
    boolean isPlaying();
    void stop();
    void loadSong(Song song);
    void play();
    void playFromPosition(Song song, int orderIndex, int rowIndex);
    void reload(Song song);
}
```

Add `onSongEdited()` to `SongTabCoordinator`:

```java
/**
 * Called when the song model is edited. If currently playing, reloads
 * the song to reflect changes while preserving the playback position.
 */
void onSongEdited(Song song) {
    if (playback.isPlaying()) {
        playback.reload(song);
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestSongTabCoordinator`
Expected: All PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/SongTabCoordinator.java app/src/test/java/com/opensmpsdeck/ui/TestSongTabCoordinator.java
git commit -m "feat: add onSongEdited() to SongTabCoordinator for live reload on edit"
```

---

### Task 6: Mute/solo reset on TrackerGrid.setSong()

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java:136-140`

**Context:** `TrackerGrid.setSong()` currently preserves stale mute state (`channelMuted[]` and `soloChannel`). The spec says mute state should reset on song load (but NOT on live reload — that's handled by the coordinator's snapshot/restore in a future task).

**Step 1: Write the minimal implementation**

In `TrackerGrid.setSong()` (line 136), add mute reset before `refreshDisplay()`:

```java
public void setSong(Song song) {
    this.song = song;
    this.currentPatternIndex = 0;
    Arrays.fill(channelMuted, false);
    soloChannel = -1;
    applyMuteState();
    refreshDisplay();
}
```

Ensure `java.util.Arrays` is imported (check existing imports).

**Step 2: Run all existing tests to verify nothing breaks**

Run: `mvn test -pl app`
Expected: All PASS

**Step 3: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java
git commit -m "fix: reset mute/solo state on TrackerGrid.setSong()"
```

---

### Task 7: WavExporter configurable extend/inset fade model

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/io/WavExporter.java`
- Modify: `app/src/test/java/com/opensmpsdeck/io/TestWavExporter.java`

**Context:** Current WavExporter applies a linear fade over the entire final loop (hardcoded). The design replaces this with three controls: `fadeEnabled` (boolean), `fadeDurationSeconds` (double), and `fadeExtend` (boolean for extend vs. inset mode).

- **Extend (fadeExtend=true):** After all loops finish, continue rendering for fadeDurationSeconds with a fade from full to silence. Total audio = loops + extension.
- **Inset (fadeExtend=false):** Fade the last fadeDurationSeconds of the rendered audio. Total audio = loops, final N seconds faded.
- **No fade (fadeEnabled=false):** Export clean with no fade at all.

**Step 1: Write the failing tests**

Add to `TestWavExporter.java`:

```java
@Test
void testFadeEnabledFalseProducesCleanOutput() throws IOException {
    Song song = createTerminatingSong();
    File withFade = new File(tempDir, "with-fade.wav");
    File noFade = new File(tempDir, "no-fade.wav");

    WavExporter exporter1 = new WavExporter();
    exporter1.setLoopCount(2);
    exporter1.setMaxDurationSeconds(30);
    exporter1.setFadeEnabled(true);
    exporter1.setFadeDurationSeconds(3.0);
    exporter1.setFadeExtend(false);
    exporter1.export(song, withFade);

    WavExporter exporter2 = new WavExporter();
    exporter2.setLoopCount(2);
    exporter2.setMaxDurationSeconds(30);
    exporter2.setFadeEnabled(false);
    exporter2.export(song, noFade);

    // The no-fade export's final quarter should be louder than faded export
    double fadedRms = computeLastQuarterAcRms(Files.readAllBytes(withFade.toPath()));
    double cleanRms = computeLastQuarterAcRms(Files.readAllBytes(noFade.toPath()));
    assertTrue(cleanRms > fadedRms,
            "No-fade last-quarter AC RMS (" + cleanRms + ") should exceed faded ("
                    + fadedRms + ")");
}

@Test
void testExtendModeProducesLongerOutput() throws IOException {
    Song song = createTerminatingSong();
    File extendFile = new File(tempDir, "extend.wav");
    File insetFile = new File(tempDir, "inset.wav");

    WavExporter extendExporter = new WavExporter();
    extendExporter.setLoopCount(2);
    extendExporter.setMaxDurationSeconds(30);
    extendExporter.setFadeEnabled(true);
    extendExporter.setFadeDurationSeconds(2.0);
    extendExporter.setFadeExtend(true);
    extendExporter.export(song, extendFile);

    WavExporter insetExporter = new WavExporter();
    insetExporter.setLoopCount(2);
    insetExporter.setMaxDurationSeconds(30);
    insetExporter.setFadeEnabled(true);
    insetExporter.setFadeDurationSeconds(2.0);
    insetExporter.setFadeExtend(false);
    insetExporter.export(song, insetFile);

    assertTrue(extendFile.length() > insetFile.length(),
            "Extend mode (" + extendFile.length()
                    + ") should produce longer file than inset mode ("
                    + insetFile.length() + ")");
}

@Test
void testFadeDurationGetterSetter() {
    WavExporter exporter = new WavExporter();
    exporter.setFadeDurationSeconds(5.0);
    assertEquals(5.0, exporter.getFadeDurationSeconds(), 0.001);

    exporter.setFadeDurationSeconds(0.0);
    assertEquals(0.1, exporter.getFadeDurationSeconds(), 0.001,
            "Should clamp to minimum 0.1");

    exporter.setFadeDurationSeconds(-1.0);
    assertEquals(0.1, exporter.getFadeDurationSeconds(), 0.001,
            "Negative values should clamp to 0.1");
}
```

Add the helper (reuse existing `computeAcRms` pattern):

```java
private double computeLastQuarterAcRms(byte[] wav) {
    int pcmStart = 44;
    int sampleCount = (wav.length - pcmStart) / 2;
    if (sampleCount <= 0) return 0.0;
    int quarterSamples = sampleCount / 4;
    int startByte = pcmStart + (sampleCount - quarterSamples) * 2;

    long sum = 0;
    int count = 0;
    for (int i = 0; i < quarterSamples; i++) {
        int bytePos = startByte + i * 2;
        if (bytePos + 1 >= wav.length) break;
        short sample = (short) ((wav[bytePos] & 0xFF) | (wav[bytePos + 1] << 8));
        sum += sample;
        count++;
    }
    double mean = (double) sum / count;

    double sumSquares = 0;
    for (int i = 0; i < quarterSamples; i++) {
        int bytePos = startByte + i * 2;
        if (bytePos + 1 >= wav.length) break;
        short sample = (short) ((wav[bytePos] & 0xFF) | (wav[bytePos + 1] << 8));
        double ac = sample - mean;
        sumSquares += ac * ac;
    }
    return Math.sqrt(sumSquares / count);
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestWavExporter#testFadeEnabledFalseProducesCleanOutput`
Expected: Compilation error — new methods don't exist.

**Step 3: Write minimal implementation**

Replace the fade-related code in `WavExporter.java`:

Add new fields:

```java
private boolean fadeEnabled = true;
private double fadeDurationSeconds = 3.0;
private boolean fadeExtend = true;
```

Add accessors:

```java
public void setFadeEnabled(boolean fadeEnabled) {
    this.fadeEnabled = fadeEnabled;
}

public boolean isFadeEnabled() {
    return fadeEnabled;
}

public void setFadeDurationSeconds(double seconds) {
    this.fadeDurationSeconds = Math.max(0.1, seconds);
}

public double getFadeDurationSeconds() {
    return fadeDurationSeconds;
}

public void setFadeExtend(boolean extend) {
    this.fadeExtend = extend;
}

public boolean isFadeExtend() {
    return fadeExtend;
}
```

Replace the `export()` method body. Key changes:
1. Remove the per-loop `fadeStartFrame`/`fadeLengthFrames` tracking.
2. After the main loop, if fadeEnabled and fadeExtend: render additional fadeDurationSeconds of audio with linear fade.
3. After the main loop, if fadeEnabled and !fadeExtend: apply linear fade to the last fadeDurationSeconds of the PCM buffer.
4. If !fadeEnabled: do nothing (clean export).

```java
public void export(Song song, File outputFile) throws IOException {
    PlaybackEngine engine = new PlaybackEngine();

    ByteArrayOutputStream pcmData = new ByteArrayOutputStream();
    int maxFrames = SAMPLE_RATE * maxDurationSeconds;
    int totalFrames = 0;

    for (int loop = 0; loop < loopCount; loop++) {
        engine.loadSong(song);

        if (mutedChannels != null) {
            for (int ch = 0; ch < mutedChannels.length; ch++) {
                if (mutedChannels[ch]) {
                    if (ch < 6) {
                        engine.setFmMute(ch, true);
                    } else {
                        engine.setPsgMute(ch - 6, true);
                    }
                }
            }
        }

        short[] buffer = new short[BUFFER_FRAMES * CHANNELS];

        while (totalFrames < maxFrames) {
            engine.renderBuffer(buffer);
            if (engine.getDriver().isComplete()) break;

            for (short sample : buffer) {
                pcmData.write(sample & 0xFF);
                pcmData.write((sample >> 8) & 0xFF);
            }
            totalFrames += BUFFER_FRAMES;
        }
    }

    // Extend mode: render additional seconds with fade applied per-buffer
    if (fadeEnabled && fadeExtend) {
        int fadeTotalFrames = (int) (fadeDurationSeconds * SAMPLE_RATE);
        int fadeRendered = 0;
        short[] buffer = new short[BUFFER_FRAMES * CHANNELS];

        while (fadeRendered < fadeTotalFrames && totalFrames < maxFrames) {
            engine.renderBuffer(buffer);
            if (engine.getDriver().isComplete()) break;

            for (int i = 0; i < buffer.length; i += 2) {
                int frameInFade = fadeRendered + (i / CHANNELS);
                float gain = 1.0f - (float) frameInFade / fadeTotalFrames;
                if (gain < 0) gain = 0;
                buffer[i] = (short) (buffer[i] * gain);
                buffer[i + 1] = (short) (buffer[i + 1] * gain);
            }

            for (short sample : buffer) {
                pcmData.write(sample & 0xFF);
                pcmData.write((sample >> 8) & 0xFF);
            }
            fadeRendered += BUFFER_FRAMES;
            totalFrames += BUFFER_FRAMES;
        }
    }

    byte[] pcm = pcmData.toByteArray();

    // Inset mode: fade the last N seconds of already-rendered PCM
    if (fadeEnabled && !fadeExtend) {
        int fadeSampleCount = (int) (fadeDurationSeconds * SAMPLE_RATE);
        int clampedFadeFrames = Math.min(fadeSampleCount, totalFrames);
        int fadeStartFrame = totalFrames - clampedFadeFrames;
        applyFadeOut(pcm, fadeStartFrame, clampedFadeFrames);
    }

    // Write WAV file
    try (DataOutputStream dos = new DataOutputStream(
            new BufferedOutputStream(new FileOutputStream(outputFile)))) {
        writeWavHeader(dos, pcm.length);
        dos.write(pcm);
    }
}
```

The existing `applyFadeOut()` method remains unchanged — it already applies linear fade over a given frame range.

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestWavExporter`
Expected: All PASS (existing tests + new tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/WavExporter.java app/src/test/java/com/opensmpsdeck/io/TestWavExporter.java
git commit -m "feat: configurable extend/inset fade model for WavExporter"
```

---

### Task 8: WAV Export Dialog

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindowFileActions.java:118-157`

**Context:** The current `onExportWav()` exports directly without any dialog for loop count or fade settings. Replace with a dialog containing the controls from the design spec.

**Step 1: Write the dialog implementation**

Replace the WAV export flow in `MainWindowFileActions.onExportWav()`. After the file chooser returns a non-null file, show a dialog before exporting:

```java
void onExportWav() {
    SongTab tab = getActiveSongTab();
    if (tab == null) return;
    if (tab.getSong().getPatterns().isEmpty()) {
        showError("Cannot export", "Song has no patterns to export.");
        return;
    }
    File file = showFileDialog("Export WAV Audio", "WAV Audio", "*.wav", true);
    if (file == null) return;

    // Build dialog
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("WAV Export Settings");
    dialog.setHeaderText("Configure export options");
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

    Spinner<Integer> loopSpinner = new Spinner<>(1, 99, 2);
    loopSpinner.setEditable(true);

    CheckBox fadeCheckBox = new CheckBox("Enable fade out");
    fadeCheckBox.setSelected(true);

    Spinner<Double> fadeSpinner = new Spinner<>(0.1, 30.0, 3.0, 0.1);
    fadeSpinner.setEditable(true);

    ComboBox<String> fadeModeCombo = new ComboBox<>();
    fadeModeCombo.getItems().addAll("Extend", "Inset");
    fadeModeCombo.setValue("Extend");

    fadeCheckBox.selectedProperty().addListener((obs, old, checked) -> {
        fadeSpinner.setDisable(!checked);
        fadeModeCombo.setDisable(!checked);
    });

    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(10);
    grid.add(new Label("Loop Count:"), 0, 0);
    grid.add(loopSpinner, 1, 0);
    grid.add(fadeCheckBox, 0, 1, 2, 1);
    grid.add(new Label("Fade Duration (seconds):"), 0, 2);
    grid.add(fadeSpinner, 1, 2);
    grid.add(new Label("Fade Mode:"), 0, 3);
    grid.add(fadeModeCombo, 1, 3);
    dialog.getDialogPane().setContent(grid);

    Optional<ButtonType> result = dialog.showAndWait();
    if (result.isEmpty() || result.get() != ButtonType.OK) return;

    int loops = loopSpinner.getValue();
    boolean fadeEnabled = fadeCheckBox.isSelected();
    double fadeDuration = fadeSpinner.getValue();
    boolean fadeExtend = "Extend".equals(fadeModeCombo.getValue());

    boolean[] mutedChannels = new boolean[10];
    TrackerGrid trackerGrid = tab.getTrackerGrid();
    for (int ch = 0; ch < 10; ch++) {
        mutedChannels[ch] = trackerGrid.isChannelMuted(ch);
    }
    Song song = tab.getSong();

    Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
    progressAlert.setTitle("Exporting WAV");
    progressAlert.setHeaderText("Rendering audio...");
    progressAlert.setContentText("Please wait while the song is exported.");
    progressAlert.getButtonTypes().clear();
    progressAlert.show();

    Thread exportThread = new Thread(() -> {
        try {
            WavExporter exporter = new WavExporter();
            exporter.setLoopCount(loops);
            exporter.setMutedChannels(mutedChannels);
            exporter.setFadeEnabled(fadeEnabled);
            exporter.setFadeDurationSeconds(fadeDuration);
            exporter.setFadeExtend(fadeExtend);
            exporter.export(song, file);
            Platform.runLater(progressAlert::close);
        } catch (IOException ex) {
            Platform.runLater(() -> {
                progressAlert.close();
                showError("Failed to export WAV", ex.getMessage());
            });
        }
    }, "WAV-Export");
    exportThread.setDaemon(true);
    exportThread.start();
}
```

Add necessary JavaFX imports at the top of the file:

```java
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
```

**Step 2: Run existing tests to verify nothing breaks**

Run: `mvn test -pl app`
Expected: All PASS

**Step 3: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/MainWindowFileActions.java
git commit -m "feat: WAV export dialog with loop count, fade toggle, duration, and mode"
```

---

### Task 9: TrackerGrid playback cursor

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java`

**Context:** TrackerGrid currently has only an edit cursor (`cursorRow`, `cursorChannel`). Add a separate playback cursor that renders as a distinct highlight.

**Step 1: Add playback cursor state and accessors**

Add fields near the existing cursor fields (around line 100):

```java
private int playbackRow = -1;
private int playbackOrderRow = -1;
```

Add public methods:

```java
/** Set the playback highlight row within the current pattern. */
public void setPlaybackRow(int row) {
    this.playbackRow = row;
    refreshDisplay();
}

/** Set the playback order row for order list highlighting. */
public void setPlaybackOrderRow(int orderRow) {
    this.playbackOrderRow = orderRow;
}

/** Get the current playback order row, or -1 if not playing. */
public int getPlaybackOrderRow() {
    return playbackOrderRow;
}

/** Clear the playback cursor (called on stop). */
public void clearPlaybackCursor() {
    this.playbackRow = -1;
    this.playbackOrderRow = -1;
    refreshDisplay();
}
```

**Step 2: Add playback cursor rendering**

In the `drawGrid()` or equivalent rendering method (find the method that draws rows using `canvas.getGraphicsContext2D()`), add a playback row highlight. Look for where the edit cursor row is drawn (a colored background for `cursorRow`). Add a similar block for `playbackRow`:

```java
// Playback cursor highlight (teal bar, semi-transparent)
if (row == playbackRow && playbackRow >= 0) {
    gc.setFill(Color.rgb(0, 180, 180, 0.25));
    gc.fillRect(x, y, rowWidth, rowHeight);
}
```

Place this BEFORE the edit cursor rendering so the edit cursor draws on top.

**Step 3: Run existing tests**

Run: `mvn test -pl app`
Expected: All PASS

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java
git commit -m "feat: add playback cursor highlight to TrackerGrid"
```

---

### Task 10: Playback cursor sync polling

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/SongTabCoordinator.java`
- Modify: `app/src/main/java/com/opensmpsdeck/audio/PlaybackEngine.java` (if needed for interface)

**Context:** The coordinator needs a polling mechanism (~15 Hz) that queries the playback position and updates TrackerGrid's playback cursor and OrderListPanel's selection. The design calls for a JavaFX `AnimationTimer` or `Timeline`.

Since SongTabCoordinator is unit-tested without JavaFX, the polling mechanism should be wired externally (in MainWindow or SongTab). The coordinator provides the update logic; the timer is wired in the UI layer.

**Step 1: Add PlaybackGateway.getPlaybackPosition()**

Update the `PlaybackGateway` interface:

```java
interface PlaybackGateway {
    boolean isPlaying();
    void stop();
    void loadSong(Song song);
    void play();
    void playFromPosition(Song song, int orderIndex, int rowIndex);
    void reload(Song song);
    PlaybackEngine.PlaybackPosition getPlaybackPosition();
}
```

**Step 2: Add UI callback interface to SongTabCoordinator**

```java
interface PlaybackCursorListener {
    void onPlaybackCursorMoved(int orderRow, int patternRow);
    void onPlaybackCursorCleared();
}

private PlaybackCursorListener cursorListener;

void setPlaybackCursorListener(PlaybackCursorListener listener) {
    this.cursorListener = listener;
}
```

**Step 3: Add update method called by the polling timer**

```java
/**
 * Called periodically (~15 Hz) during playback to update cursor position.
 * Should be called from the JavaFX application thread.
 */
void updatePlaybackCursor() {
    if (!playback.isPlaying()) {
        if (cursorListener != null) {
            cursorListener.onPlaybackCursorCleared();
        }
        return;
    }
    PlaybackEngine.PlaybackPosition pos = playback.getPlaybackPosition();
    if (pos != null && cursorListener != null) {
        cursorListener.onPlaybackCursorMoved(pos.orderIndex(), pos.rowIndex());
    }
}
```

**Step 4: Wire the polling timer in the UI layer**

In `MainWindow` (or wherever the coordinator is created and playback starts), add a `javafx.animation.Timeline`:

```java
Timeline cursorPollTimer = new Timeline(new KeyFrame(
    Duration.millis(67), // ~15 Hz
    e -> coordinator.updatePlaybackCursor()
));
cursorPollTimer.setCycleCount(Timeline.INDEFINITE);
```

Start the timer when playback starts, stop when playback stops. Wire the `PlaybackCursorListener` to update `TrackerGrid.setPlaybackRow()` and `OrderListPanel` selection.

**Step 5: Update FakePlaybackGateway in tests**

Add `getPlaybackPosition()` to the fake:

```java
@Override
public PlaybackEngine.PlaybackPosition getPlaybackPosition() {
    return null; // Tests don't need real position data
}
```

**Step 6: Run tests**

Run: `mvn test -pl app`
Expected: All PASS

**Step 7: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/SongTabCoordinator.java app/src/test/java/com/opensmpsdeck/ui/TestSongTabCoordinator.java
git commit -m "feat: playback cursor sync with polling update in SongTabCoordinator"
```

---

### Task 11: Wire live reload through SongTab dirty callback

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/SongTab.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java` (or wherever SongTabCoordinator is wired)

**Context:** `SongTab.setDirty(true)` is called when TrackerGrid, InstrumentPanel, or OrderListPanel fires `onDirty`. We need to additionally call `coordinator.onSongEdited(song)` when dirty is set.

**Step 1: Add an onEdited callback to SongTab**

```java
private Runnable onEdited;

public void setOnEdited(Runnable callback) {
    this.onEdited = callback;
}
```

Update `buildContent()` to wire onDirty → setDirty + onEdited:

```java
Runnable dirtyAndEdited = () -> {
    setDirty(true);
    if (onEdited != null) onEdited.run();
};
trackerGrid.setOnDirty(dirtyAndEdited);
instrumentPanel.setOnDirty(dirtyAndEdited);
orderListPanel.setOnDirty(dirtyAndEdited);
```

**Step 2: Wire in MainWindow**

Where the coordinator is available:

```java
songTab.setOnEdited(() -> coordinator.onSongEdited(songTab.getSong()));
```

**Step 3: Run tests**

Run: `mvn test -pl app`
Expected: All PASS

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/SongTab.java app/src/main/java/com/opensmpsdeck/ui/MainWindow.java
git commit -m "feat: wire song dirty callback to coordinator for live reload on edit"
```

---

### Task 12: Update user guide for WAV export changes

**Files:**
- Modify: `docs/user-guide/09-playback-and-export.md`

**Context:** The WAV export section needs to document the new dialog controls: loop count, fade out checkbox, fade duration, and fade mode (Extend/Inset).

**Step 1: Update the WAV export section**

Find the existing WAV export content and update it to describe:
- The dialog that appears after choosing the output file
- Loop Count spinner (default 2, range 1-99)
- Fade Out checkbox (default checked)
- Fade Duration in seconds (default 3.0, range 0.1-30.0)
- Fade Mode: Extend (continues past final loop) vs. Inset (fades within final loop)
- Practical guidance: use Extend for clean loop-to-silence transitions, use Inset to trim endings

**Step 2: Commit**

```bash
git add docs/user-guide/09-playback-and-export.md
git commit -m "docs: update WAV export chapter for configurable fade dialog"
```

---

### Task 13: Run full test suite and verify

**Step 1: Run all tests**

Run: `mvn test`
Expected: All PASS across both synth-core and app modules.

**Step 2: Run git status and review**

Run: `git status` and `git log --oneline -15`
Verify all changes are committed, no untracked files.
