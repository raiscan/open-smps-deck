# MIDI Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** File → Import MIDI… converts per-stem `.mid` files into a new hierarchical Song (voice separation, tempo fitting, GM drum mapping, phrase dedup) with a mapping preview dialog.

**Architecture:** A pure pipeline in new package `com.opensmpsdeck.io.midi` (MidiReader → VoiceSeparator → TempoFitter/NoteQuantizer → GmDrumMapper → MidiPhraseEncoder → MidiSongBuilder) plus one JavaFX dialog (`MidiImportDialog`) and a menu hook in `MainWindowFileActions`. Everything downstream of the produced `Song` is existing machinery.

**Tech Stack:** Java 21, `javax.sound.midi` (JDK), JavaFX 21, JUnit 5. No new Maven dependencies.

**Spec:** `docs/plans/2026-06-11-midi-import-design.md`

**Conventions used throughout:**
- Model classes: `com.opensmpsdeck.model` (`Song`, `HierarchicalArrangement`, `Chain`, `ChainEntry`, `Phrase`, `PhraseLibrary`, `ChannelType`, `SmpsMode`, `FmVoice`, `DacSample`)
- Flags: `com.opensmps.smps.SmpsCoordFlags` (`SET_VOICE=0xEF`, `PSG_INSTRUMENT=0xF5`, `TIE=0xE7`, `STOP=0xF2`, `PSG_NOISE=0xF3`)
- Note byte formula: SMPS `0x81` = "C-0" and `InstrumentPreviewPlayer.DEFAULT_NOTE = 0xB1` = C4 = MIDI 60, so **noteByte = 0x81 + midiPitch − 12** (+12 × octaveShift). Valid noteByte range 0x81–0xDF ⇒ MIDI 12–106 at shift 0.
- Durations: a duration byte `d` lasts `d × dividingTiming` sequencer ticks; sequencer ticks per second = `60 × ticksPerFrame(mode, tempoByte)`.
- Run tests with `mvn test -pl app -Dtest=<ClassName>` from the repo root.

---

### Task 1: NoteEvent model + MidiReader

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/NoteEvent.java`
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/MidiStem.java`
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/MidiReader.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/midi/TestMidiReader.java`

- [ ] **Step 1: Write the failing test**

Tests build `javax.sound.midi.Sequence` objects in memory and write them to a temp `.mid` with `MidiSystem.write(seq, 1, file)` — no binary fixtures needed.

```java
package com.opensmpsdeck.io.midi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.*;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class TestMidiReader {

    @TempDir
    File tempDir;

    private static MidiEvent noteOn(int ch, int pitch, int vel, long tick) throws Exception {
        return new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, pitch, vel), tick);
    }

    private static MidiEvent noteOff(int ch, int pitch, long tick) throws Exception {
        return new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, ch, pitch, 0), tick);
    }

    private File write(Sequence seq) throws Exception {
        File f = new File(tempDir, "t.mid");
        MidiSystem.write(seq, 1, f);
        return f;
    }

    @Test
    void pairsNoteOnAndOff() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(noteOn(0, 60, 100, 0));
        t.add(noteOff(0, 60, 480));
        MidiStem stem = MidiReader.read(write(seq));

        assertEquals(480, stem.ppq());
        assertEquals(1, stem.tracks().size());
        NoteEvent n = stem.tracks().get(0).notes().get(0);
        assertEquals(0, n.startTick());
        assertEquals(480, n.durationTicks());
        assertEquals(60, n.pitch());
        assertEquals(100, n.velocity());
    }

    @Test
    void velocityZeroNoteOnIsNoteOff() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(noteOn(0, 60, 100, 0));
        t.add(noteOn(0, 60, 0, 240));   // running-status style note-off
        MidiStem stem = MidiReader.read(write(seq));
        assertEquals(240, stem.tracks().get(0).notes().get(0).durationTicks());
    }

    @Test
    void danglingNoteOnClosesAtTrackEnd() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(noteOn(0, 64, 90, 100));
        t.add(noteOn(0, 60, 90, 0));    // ensure track end > 100
        t.add(noteOff(0, 60, 960));
        MidiStem stem = MidiReader.read(write(seq));
        NoteEvent dangling = stem.tracks().get(0).notes().stream()
                .filter(n -> n.pitch() == 64).findFirst().orElseThrow();
        assertTrue(dangling.durationTicks() > 0);
    }

    @Test
    void samePitchOverlapMerges() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(noteOn(0, 60, 100, 0));
        t.add(noteOn(0, 60, 100, 240)); // re-strike before off
        t.add(noteOff(0, 60, 480));
        MidiStem stem = MidiReader.read(write(seq));
        var notes = stem.tracks().get(0).notes();
        assertEquals(2, notes.size());
        assertEquals(240, notes.get(0).durationTicks()); // first closed at re-strike
    }

    @Test
    void channelTenIsDrumTrack() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        t.add(noteOn(9, 36, 100, 0));   // MIDI channel 10 = index 9
        t.add(noteOff(9, 36, 120));
        MidiStem stem = MidiReader.read(write(seq));
        assertTrue(stem.tracks().get(0).drumTrack());
    }

    @Test
    void readsTempoMapAndTimeSignature() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track t = seq.createTrack();
        // tempo 120 BPM = 500000 us/quarter
        t.add(new MidiEvent(new MetaMessage(0x51, new byte[]{0x07, (byte) 0xA1, 0x20}, 3), 0));
        // time sig 3/4: num=3, denomPow=2, metronome=24, 32nds=8
        t.add(new MidiEvent(new MetaMessage(0x58, new byte[]{3, 2, 24, 8}, 4), 0));
        t.add(noteOn(0, 60, 100, 0));
        t.add(noteOff(0, 60, 480));
        MidiStem stem = MidiReader.read(write(seq));
        assertEquals(500000, stem.tempoMap().get(0).microsecondsPerQuarter());
        assertEquals(3, stem.timeSignature().numerator());
        assertEquals(4, stem.timeSignature().denominator());
    }

    @Test
    void notelessTracksAreSkipped() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        seq.createTrack(); // tempo-only track, no notes
        Track t = seq.createTrack();
        t.add(noteOn(0, 60, 100, 0));
        t.add(noteOff(0, 60, 480));
        MidiStem stem = MidiReader.read(write(seq));
        assertEquals(1, stem.tracks().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestMidiReader`
Expected: COMPILE ERROR ("cannot find symbol: class MidiReader")

- [ ] **Step 3: Write the implementation**

`NoteEvent.java`:

```java
package com.opensmpsdeck.io.midi;

/** A single MIDI note in absolute ticks. */
public record NoteEvent(long startTick, long durationTicks, int pitch, int velocity) {

    public long endTick() {
        return startTick + durationTicks;
    }
}
```

`MidiStem.java`:

```java
package com.opensmpsdeck.io.midi;

import java.util.List;
import java.util.Set;

/** Neutral model of one parsed .mid file (one stem). */
public record MidiStem(String name, int ppq, List<TempoEvent> tempoMap,
                       TimeSignature timeSignature, List<MidiNoteTrack> tracks) {

    public record TempoEvent(long tick, int microsecondsPerQuarter) {}

    public record TimeSignature(int numerator, int denominator) {}

    public record MidiNoteTrack(boolean drumTrack, Set<Integer> programs, List<NoteEvent> notes) {}

    /** Last tick of any note across all tracks. */
    public long totalTicks() {
        return tracks.stream()
                .flatMap(t -> t.notes().stream())
                .mapToLong(NoteEvent::endTick)
                .max().orElse(0);
    }
}
```

`MidiReader.java`:

```java
package com.opensmpsdeck.io.midi;

import javax.sound.midi.*;
import java.io.File;
import java.io.IOException;
import java.util.*;

/** Parses a .mid file into a MidiStem via javax.sound.midi. */
public final class MidiReader {

    private static final int META_TEMPO = 0x51;
    private static final int META_TIME_SIG = 0x58;
    private static final int META_TRACK_NAME = 0x03;
    private static final int DRUM_CHANNEL = 9;

    private MidiReader() {}

    public static MidiStem read(File file) throws IOException {
        Sequence seq;
        try {
            seq = MidiSystem.getSequence(file);
        } catch (InvalidMidiDataException e) {
            throw new IOException("Not a valid MIDI file: " + file.getName(), e);
        }
        if (seq.getDivisionType() != Sequence.PPQ) {
            throw new IOException("SMPTE-division MIDI is not supported: " + file.getName());
        }

        List<MidiStem.TempoEvent> tempoMap = new ArrayList<>();
        MidiStem.TimeSignature timeSig = null;
        String name = stripExtension(file.getName());
        List<MidiStem.MidiNoteTrack> noteTracks = new ArrayList<>();

        for (Track track : seq.getTracks()) {
            // key = channel << 8 | pitch  → (startTick, velocity)
            Map<Integer, long[]> active = new HashMap<>();
            List<NoteEvent> notes = new ArrayList<>();
            Set<Integer> programs = new TreeSet<>();
            boolean drum = false;
            long lastTick = 0;

            for (int i = 0; i < track.size(); i++) {
                MidiEvent ev = track.get(i);
                lastTick = Math.max(lastTick, ev.getTick());
                MidiMessage msg = ev.getMessage();
                if (msg instanceof MetaMessage meta) {
                    if (meta.getType() == META_TEMPO && meta.getData().length == 3) {
                        byte[] d = meta.getData();
                        int usPerQ = ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
                        tempoMap.add(new MidiStem.TempoEvent(ev.getTick(), usPerQ));
                    } else if (meta.getType() == META_TIME_SIG && timeSig == null
                            && meta.getData().length >= 2) {
                        byte[] d = meta.getData();
                        timeSig = new MidiStem.TimeSignature(d[0] & 0xFF, 1 << (d[1] & 0xFF));
                    }
                } else if (msg instanceof ShortMessage sm) {
                    int cmd = sm.getCommand();
                    int ch = sm.getChannel();
                    if (cmd == ShortMessage.PROGRAM_CHANGE) {
                        programs.add(sm.getData1());
                    } else if (cmd == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                        if (ch == DRUM_CHANNEL) drum = true;
                        int key = (ch << 8) | sm.getData1();
                        long[] prev = active.put(key, new long[]{ev.getTick(), sm.getData2()});
                        if (prev != null) { // same-pitch overlap: close previous at this tick
                            addNote(notes, prev, sm.getData1(), ev.getTick());
                        }
                    } else if (cmd == ShortMessage.NOTE_OFF
                            || (cmd == ShortMessage.NOTE_ON && sm.getData2() == 0)) {
                        int key = (ch << 8) | sm.getData1();
                        long[] on = active.remove(key);
                        if (on != null) addNote(notes, on, sm.getData1(), ev.getTick());
                    }
                }
            }
            // dangling note-ons close at track end
            for (Map.Entry<Integer, long[]> e : active.entrySet()) {
                addNote(notes, e.getValue(), e.getKey() & 0xFF, lastTick);
            }
            if (!notes.isEmpty()) {
                notes.sort(Comparator.comparingLong(NoteEvent::startTick)
                        .thenComparing(Comparator.comparingInt(NoteEvent::pitch).reversed()));
                noteTracks.add(new MidiStem.MidiNoteTrack(drum, programs, List.copyOf(notes)));
            }
        }

        if (timeSig == null) timeSig = new MidiStem.TimeSignature(4, 4);
        tempoMap.sort(Comparator.comparingLong(MidiStem.TempoEvent::tick));
        return new MidiStem(name, seq.getResolution(), List.copyOf(tempoMap), timeSig,
                List.copyOf(noteTracks));
    }

    private static void addNote(List<NoteEvent> notes, long[] on, int pitch, long offTick) {
        long dur = Math.max(1, offTick - on[0]);
        notes.add(new NoteEvent(on[0], dur, pitch, (int) on[1]));
    }

    private static String stripExtension(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestMidiReader`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/midi app/src/test/java/com/opensmpsdeck/io/midi
git commit -m "feat: MIDI file reader producing neutral NoteEvent model"
```

---

### Task 2: TickTimeMapper (tick → seconds via tempo map)

Needed for Phase 2's WAV slicing and for the TempoFitter's weighted-median BPM.

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/TickTimeMapper.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/midi/TestTickTimeMapper.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.io.midi;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestTickTimeMapper {

    @Test
    void constantTempo() {
        // 120 BPM = 500000 us/quarter, 480 ppq → 1 quarter = 0.5 s
        var m = new TickTimeMapper(480, List.of(new MidiStem.TempoEvent(0, 500000)));
        assertEquals(0.0, m.secondsAt(0), 1e-9);
        assertEquals(0.5, m.secondsAt(480), 1e-9);
        assertEquals(2.0, m.secondsAt(1920), 1e-9);
    }

    @Test
    void tempoChangeMidway() {
        // 120 BPM for first quarter, then 60 BPM (1000000 us/q)
        var m = new TickTimeMapper(480, List.of(
                new MidiStem.TempoEvent(0, 500000),
                new MidiStem.TempoEvent(480, 1000000)));
        assertEquals(0.5, m.secondsAt(480), 1e-9);
        assertEquals(1.5, m.secondsAt(960), 1e-9);
    }

    @Test
    void emptyTempoMapDefaultsTo120() {
        var m = new TickTimeMapper(480, List.of());
        assertEquals(0.5, m.secondsAt(480), 1e-9);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestTickTimeMapper`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.io.midi;

import java.util.ArrayList;
import java.util.List;

/** Converts absolute MIDI ticks to seconds using a tempo map. */
public final class TickTimeMapper {

    private static final int DEFAULT_US_PER_QUARTER = 500000; // 120 BPM

    private final int ppq;
    private final long[] ticks;
    private final double[] seconds;
    private final int[] usPerQuarter;

    public TickTimeMapper(int ppq, List<MidiStem.TempoEvent> tempoMap) {
        this.ppq = ppq;
        List<MidiStem.TempoEvent> map = new ArrayList<>(tempoMap);
        if (map.isEmpty() || map.get(0).tick() > 0) {
            map.add(0, new MidiStem.TempoEvent(0, DEFAULT_US_PER_QUARTER));
        }
        ticks = new long[map.size()];
        seconds = new double[map.size()];
        usPerQuarter = new int[map.size()];
        double acc = 0;
        for (int i = 0; i < map.size(); i++) {
            ticks[i] = map.get(i).tick();
            usPerQuarter[i] = map.get(i).microsecondsPerQuarter();
            if (i > 0) {
                acc += (ticks[i] - ticks[i - 1]) * usPerQuarter[i - 1] / 1e6 / ppq;
            }
            seconds[i] = acc;
        }
    }

    public double secondsAt(long tick) {
        int i = ticks.length - 1;
        while (i > 0 && ticks[i] > tick) i--;
        return seconds[i] + (tick - ticks[i]) * usPerQuarter[i] / 1e6 / ppq;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestTickTimeMapper`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/midi/TickTimeMapper.java app/src/test/java/com/opensmpsdeck/io/midi/TestTickTimeMapper.java
git commit -m "feat: tick-to-seconds mapper over MIDI tempo maps"
```

---

### Task 3: VoiceSeparator

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/VoiceSeparator.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/midi/TestVoiceSeparator.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.io.midi;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestVoiceSeparator {

    private static NoteEvent n(long start, long dur, int pitch) {
        return new NoteEvent(start, dur, pitch, 100);
    }

    @Test
    void chordSplitsSkylineOrder() {
        // C major triad: line 0 gets the top note
        var r = VoiceSeparator.separate(List.of(n(0, 480, 60), n(0, 480, 64), n(0, 480, 67)), 4, 15);
        assertEquals(3, r.lines().size());
        assertEquals(67, r.lines().get(0).notes().get(0).pitch());
        assertEquals(64, r.lines().get(1).notes().get(0).pitch());
        assertEquals(60, r.lines().get(2).notes().get(0).pitch());
        assertEquals(0, r.droppedNotes());
    }

    @Test
    void monophonicStaysOneLine() {
        var r = VoiceSeparator.separate(List.of(n(0, 480, 60), n(480, 480, 62), n(960, 480, 64)), 4, 15);
        assertEquals(1, r.lines().size());
        assertEquals(3, r.lines().get(0).notes().size());
    }

    @Test
    void overlappingNoteGoesToNearestFreeLine() {
        // line0 holds 72 long; a new 60 overlapping it must land on another line;
        // then 62 (nearer to 60 than to 72's history) continues that second line
        var r = VoiceSeparator.separate(List.of(
                n(0, 960, 72), n(240, 240, 60), n(960, 240, 62)), 4, 15);
        assertEquals(2, r.lines().size());
        assertEquals(List.of(62), r.lines().get(1).notes().stream()
                .skip(1).map(NoteEvent::pitch).toList());
    }

    @Test
    void overflowNotesAreDroppedAndCounted() {
        var r = VoiceSeparator.separate(List.of(
                n(0, 480, 60), n(0, 480, 64), n(0, 480, 67), n(0, 480, 72), n(0, 480, 76)), 4, 15);
        assertEquals(4, r.lines().size());
        assertEquals(1, r.droppedNotes());
    }

    @Test
    void chordEpsilonGroupsNearSimultaneousOnsets() {
        // onsets 0 and 10 ticks apart (within epsilon 15) are one chord
        var r = VoiceSeparator.separate(List.of(n(0, 480, 60), n(10, 470, 67)), 2, 15);
        assertEquals(67, r.lines().get(0).notes().get(0).pitch());
        assertEquals(60, r.lines().get(1).notes().get(0).pitch());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestVoiceSeparator`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.io.midi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits a polyphonic note list into up to maxLines monophonic lines.
 * Line 0 is the skyline (top voice); chords assign descending pitch to
 * ascending line index; isolated overlaps go to the free line with the
 * nearest previous pitch. Notes with no free line are dropped and counted.
 */
public final class VoiceSeparator {

    public record SeparatedLine(int rank, List<NoteEvent> notes) {}

    public record Result(List<SeparatedLine> lines, int droppedNotes) {}

    private VoiceSeparator() {}

    public static Result separate(List<NoteEvent> notes, int maxLines, int chordEpsilonTicks) {
        List<NoteEvent> sorted = new ArrayList<>(notes);
        sorted.sort(Comparator.comparingLong(NoteEvent::startTick)
                .thenComparing(Comparator.comparingInt(NoteEvent::pitch).reversed()));

        List<List<NoteEvent>> lines = new ArrayList<>();
        long[] lineEnd = new long[maxLines];
        int[] linePitch = new int[maxLines];
        boolean[] lineUsed = new boolean[maxLines];
        int dropped = 0;

        int i = 0;
        while (i < sorted.size()) {
            // collect a chord: onsets within epsilon of the first
            long chordStart = sorted.get(i).startTick();
            List<NoteEvent> chord = new ArrayList<>();
            while (i < sorted.size() && sorted.get(i).startTick() - chordStart <= chordEpsilonTicks) {
                chord.add(sorted.get(i));
                i++;
            }
            chord.sort(Comparator.comparingInt(NoteEvent::pitch).reversed());

            if (chord.size() > 1) {
                // chord: highest pitch → lowest-numbered free line
                int nextLine = 0;
                for (NoteEvent note : chord) {
                    int target = -1;
                    for (int l = nextLine; l < maxLines; l++) {
                        if (lineEnd[l] <= note.startTick()) { target = l; break; }
                    }
                    if (target < 0) { dropped++; continue; }
                    place(lines, lineEnd, linePitch, lineUsed, target, note);
                    nextLine = target + 1;
                }
            } else {
                NoteEvent note = chord.get(0);
                int target = -1;
                int bestDist = Integer.MAX_VALUE;
                for (int l = 0; l < maxLines; l++) {
                    if (lineEnd[l] > note.startTick()) continue;
                    // prefer previously-used lines with nearby pitch; unused lines are
                    // a distant fallback so material stays consolidated
                    int dist = lineUsed[l] ? Math.abs(linePitch[l] - note.pitch())
                                           : Integer.MAX_VALUE / 2;
                    if (dist < bestDist) { bestDist = dist; target = l; }
                }
                if (target < 0) { dropped++; }
                else place(lines, lineEnd, linePitch, lineUsed, target, note);
            }
        }

        List<SeparatedLine> result = new ArrayList<>();
        for (int l = 0; l < lines.size(); l++) {
            if (!lines.get(l).isEmpty()) {
                result.add(new SeparatedLine(l, List.copyOf(lines.get(l))));
            }
        }
        return new Result(List.copyOf(result), dropped);
    }

    private static void place(List<List<NoteEvent>> lines, long[] lineEnd, int[] linePitch,
                              boolean[] lineUsed, int l, NoteEvent note) {
        while (lines.size() <= l) lines.add(new ArrayList<>());
        lines.get(l).add(note);
        lineEnd[l] = note.endTick();
        linePitch[l] = note.pitch();
        lineUsed[l] = true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestVoiceSeparator`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/midi/VoiceSeparator.java app/src/test/java/com/opensmpsdeck/io/midi/TestVoiceSeparator.java
git commit -m "feat: skyline voice separation for polyphonic MIDI tracks"
```

---

### Task 4: TempoMath + TempoFitter + NoteQuantizer

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/TempoMath.java`
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/TempoFitter.java`
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/NoteQuantizer.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/midi/TestTempoFitter.java`

- [ ] **Step 1: VERIFY the tempo-mode semantics against the sequencer**

Open `synth-core/src/main/java/com/opensmps/smps/SmpsSequencer.java` and locate the tempo handling (search for `normalTempo` and the tempo-mode logic; CLAUDE.md maps S1→TIMEOUT, S2→OVERFLOW2, S3K→OVERFLOW). Confirm the accumulator behaviour matches what `TempoMath` below implements:
- **TIMEOUT (S1):** a countdown reloaded from the tempo byte; when it expires, that frame is stalled (no track tick).
- **OVERFLOW (S3K):** each frame ticks once; an 8-bit accumulator adds the tempo byte; on overflow that frame ticks **twice**.
- **OVERFLOW2 (S2):** an 8-bit accumulator adds the tempo byte each frame; on overflow that frame is **skipped** (no tick), otherwise it ticks once.

If the sequencer differs from this, change `TempoMath` to match the sequencer — the sequencer is authoritative. Record any correction as a comment in `TempoMath`.

- [ ] **Step 2: Write the failing test**

```java
package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.SmpsMode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestTempoFitter {

    @Test
    void overflowModeAddsExtraTicks() {
        // S3K: tempo 0x80 → overflow every 2 frames → 1.5 ticks/frame
        assertEquals(1.5, TempoMath.ticksPerFrame(SmpsMode.S3K, 0x80), 0.01);
    }

    @Test
    void overflow2ModeSkipsFrames() {
        // S2: tempo 0x80 → skip every 2nd frame → 0.5 ticks/frame
        assertEquals(0.5, TempoMath.ticksPerFrame(SmpsMode.S2, 0x80), 0.01);
    }

    @Test
    void timeoutModeStallsPeriodically() {
        // S1: tempo 4 → every 4th frame stalls → 0.75 ticks/frame
        assertEquals(0.75, TempoMath.ticksPerFrame(SmpsMode.S1, 4), 0.01);
    }

    @Test
    void weightedMedianBpmIgnoresWobble() {
        // mostly 110 BPM (545454 us/q) with a brief 113 blip
        var map = List.of(
                new MidiStem.TempoEvent(0, 531000),      // ~113 for 100 ticks
                new MidiStem.TempoEvent(100, 545454));   // 110 for the rest
        double bpm = TempoFitter.weightedMedianBpm(map, 10000, 480);
        assertEquals(110.0, bpm, 0.5);
    }

    @Test
    void fitFindsLowErrorCombo() {
        var map = List.of(new MidiStem.TempoEvent(0, 545454)); // 110 BPM
        TempoFitter.TempoFit fit = TempoFitter.fit(map, 10000, 480, SmpsMode.S2);
        assertTrue(fit.errorPercent() < 2.0, "residual error was " + fit.errorPercent());
        assertTrue(fit.unitsPerSixteenth() >= 1);
        assertTrue(fit.dividingTiming() >= 1 && fit.dividingTiming() <= 32);
        assertEquals(110.0, fit.bpm(), 0.5);
    }

    @Test
    void quantizerSnapsToSixteenthGrid() {
        // ppq 480 → 120 ticks per 16th
        var notes = List.of(
                new NoteEvent(5, 230, 60, 100),     // ≈ step 0, length 2
                new NoteEvent(475, 125, 62, 100));  // ≈ step 4, length 1
        var q = NoteQuantizer.quantize(notes, 480);
        assertEquals(0, q.get(0).startStep());
        assertEquals(2, q.get(0).lengthSteps());
        assertEquals(4, q.get(1).startStep());
        assertEquals(1, q.get(1).lengthSteps());
    }

    @Test
    void quantizerEnforcesMinimumLength() {
        var q = NoteQuantizer.quantize(List.of(new NoteEvent(0, 10, 60, 100)), 480);
        assertEquals(1, q.get(0).lengthSteps());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestTempoFitter`
Expected: COMPILE ERROR

- [ ] **Step 4: Write the implementation**

`TempoMath.java`:

```java
package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.SmpsMode;

/**
 * Effective sequencer ticks per 60 Hz frame for each SMPS tempo mode,
 * computed by simulating the driver's accumulator logic.
 * Semantics mirror SmpsSequencer (synth-core) — that code is authoritative.
 */
public final class TempoMath {

    private static final int SIM_FRAMES = 1 << 16;

    private TempoMath() {}

    public static double ticksPerFrame(SmpsMode mode, int tempoByte) {
        int ticks = 0;
        switch (mode) {
            case S1 -> { // TIMEOUT: countdown; expiry stalls the frame
                int counter = tempoByte;
                for (int f = 0; f < SIM_FRAMES; f++) {
                    if (--counter == 0) counter = tempoByte;
                    else ticks++;
                }
            }
            case S2 -> { // OVERFLOW2: overflow frame is skipped
                int acc = 0;
                for (int f = 0; f < SIM_FRAMES; f++) {
                    acc += tempoByte;
                    if (acc > 0xFF) acc &= 0xFF;
                    else ticks++;
                }
            }
            case S3K -> { // OVERFLOW: overflow frame double-ticks
                int acc = 0;
                for (int f = 0; f < SIM_FRAMES; f++) {
                    ticks++;
                    acc += tempoByte;
                    if (acc > 0xFF) { acc &= 0xFF; ticks++; }
                }
            }
        }
        return (double) ticks / SIM_FRAMES;
    }
}
```

`TempoFitter.java`:

```java
package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.SmpsMode;

import java.util.ArrayList;
import java.util.List;

/** Flattens a MIDI tempo map and fits SMPS tempo byte + dividing timing to it. */
public final class TempoFitter {

    private static final double FRAME_RATE = 60.0;
    private static final int MAX_DIVIDING_TIMING = 32;
    private static final int MAX_UNITS_PER_SIXTEENTH = 16;

    public record TempoFit(double bpm, int tempoByte, int dividingTiming,
                           int unitsPerSixteenth, double errorPercent) {}

    private TempoFitter() {}

    public static TempoFit fit(List<MidiStem.TempoEvent> tempoMap, long totalTicks,
                               int ppq, SmpsMode mode) {
        double bpm = weightedMedianBpm(tempoMap, totalTicks, ppq);
        double idealSixteenthSec = 15.0 / bpm;

        TempoFit best = null;
        for (int div = 1; div <= MAX_DIVIDING_TIMING; div++) {
            for (int tempo = 1; tempo <= 0xFF; tempo++) {
                double tpf = TempoMath.ticksPerFrame(mode, tempo);
                if (tpf <= 0) continue;
                double secondsPerUnit = div / (FRAME_RATE * tpf);
                int units = (int) Math.round(idealSixteenthSec / secondsPerUnit);
                if (units < 1 || units > MAX_UNITS_PER_SIXTEENTH) continue;
                double actual = units * secondsPerUnit;
                double errPct = Math.abs(actual - idealSixteenthSec) / idealSixteenthSec * 100;
                // prefer lower error; tie-break toward smaller duration bytes
                double score = errPct + units * 0.01 + div * 0.001;
                if (best == null || score < bestScore(best)) {
                    best = new TempoFit(bpm, tempo, div, units, errPct);
                }
            }
        }
        return best;
    }

    private static double bestScore(TempoFit f) {
        return f.errorPercent() + f.unitsPerSixteenth() * 0.01 + f.dividingTiming() * 0.001;
    }

    /** BPM whose tempo-map segments cover the median tick (duration-weighted). */
    static double weightedMedianBpm(List<MidiStem.TempoEvent> tempoMap, long totalTicks, int ppq) {
        if (tempoMap.isEmpty()) return 120.0;
        record Seg(double bpm, long ticks) {}
        List<Seg> segs = new ArrayList<>();
        for (int i = 0; i < tempoMap.size(); i++) {
            long start = tempoMap.get(i).tick();
            long end = i + 1 < tempoMap.size() ? tempoMap.get(i + 1).tick() : totalTicks;
            if (end <= start) continue;
            segs.add(new Seg(60e6 / tempoMap.get(i).microsecondsPerQuarter(), end - start));
        }
        segs.sort((a, b) -> Double.compare(a.bpm(), b.bpm()));
        long half = segs.stream().mapToLong(Seg::ticks).sum() / 2;
        long acc = 0;
        for (Seg s : segs) {
            acc += s.ticks();
            if (acc >= half) return s.bpm();
        }
        return segs.get(segs.size() - 1).bpm();
    }
}
```

`NoteQuantizer.java`:

```java
package com.opensmpsdeck.io.midi;

import java.util.ArrayList;
import java.util.List;

/** Snaps note on/off times to a 16th-note step grid. */
public final class NoteQuantizer {

    public record QuantizedNote(int startStep, int lengthSteps, int pitch, int velocity) {}

    private NoteQuantizer() {}

    public static List<QuantizedNote> quantize(List<NoteEvent> notes, int ppq) {
        double ticksPerStep = ppq / 4.0;
        List<QuantizedNote> out = new ArrayList<>(notes.size());
        for (NoteEvent n : notes) {
            int start = (int) Math.round(n.startTick() / ticksPerStep);
            int end = (int) Math.round(n.endTick() / ticksPerStep);
            out.add(new QuantizedNote(start, Math.max(1, end - start), n.pitch(), n.velocity()));
        }
        return out;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestTempoFitter`
Expected: PASS (7 tests). If the TempoMath assertions fail, recheck Step 1 — the test constants encode the expected mode semantics; fix the simulation, not the test, unless the sequencer itself disagrees with the test's model.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/midi/TempoMath.java app/src/main/java/com/opensmpsdeck/io/midi/TempoFitter.java app/src/main/java/com/opensmpsdeck/io/midi/NoteQuantizer.java app/src/test/java/com/opensmpsdeck/io/midi/TestTempoFitter.java
git commit -m "feat: SMPS tempo fitting and 16th-grid quantization for MIDI import"
```

---

### Task 5: GmDrumMapper

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/GmDrumMapper.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/midi/TestGmDrumMapper.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.io.midi;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestGmDrumMapper {

    @Test
    void defaultTableMapsCoreKit() {
        var m = GmDrumMapper.defaultMapping();
        assertEquals(GmDrumMapper.DrumTarget.DAC_KICK, m.targetFor(36));
        assertEquals(GmDrumMapper.DrumTarget.DAC_SNARE, m.targetFor(38));
        assertEquals(GmDrumMapper.DrumTarget.DAC_TOM, m.targetFor(45));
        assertEquals(GmDrumMapper.DrumTarget.NOISE_SHORT, m.targetFor(42));
        assertEquals(GmDrumMapper.DrumTarget.NOISE_LONG, m.targetFor(46));
        assertEquals(GmDrumMapper.DrumTarget.NOISE_LONG, m.targetFor(49));
        assertEquals(GmDrumMapper.DrumTarget.DROP, m.targetFor(81)); // triangle: unmapped
    }

    @Test
    void splitRoutesDacAndNoiseSeparately() {
        var notes = List.of(
                new NoteQuantizer.QuantizedNote(0, 1, 36, 100),  // kick → DAC
                new NoteQuantizer.QuantizedNote(0, 1, 42, 80),   // hat → noise
                new NoteQuantizer.QuantizedNote(2, 1, 38, 100)); // snare → DAC
        var split = GmDrumMapper.split(notes, GmDrumMapper.defaultMapping());
        assertEquals(2, split.dacHits().size());
        assertEquals(1, split.noiseHits().size());
        assertEquals(0, split.droppedPitches().size());
    }

    @Test
    void simultaneousDacHitsKeepHighestPriority() {
        // kick + snare same step → kick wins (kick > snare > tom)
        var notes = List.of(
                new NoteQuantizer.QuantizedNote(0, 1, 38, 100),
                new NoteQuantizer.QuantizedNote(0, 1, 36, 100));
        var split = GmDrumMapper.split(notes, GmDrumMapper.defaultMapping());
        assertEquals(1, split.dacHits().size());
        assertEquals(GmDrumMapper.DrumTarget.DAC_KICK, split.dacHits().get(0).target());
    }

    @Test
    void unmappedPitchesAreReported() {
        var notes = List.of(new NoteQuantizer.QuantizedNote(0, 1, 81, 100));
        var split = GmDrumMapper.split(notes, GmDrumMapper.defaultMapping());
        assertTrue(split.dacHits().isEmpty());
        assertEquals(List.of(81), split.droppedPitches());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestGmDrumMapper`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.io.midi;

import java.util.*;

/** Maps GM drum pitches to DAC sample slots and PSG noise hits. */
public final class GmDrumMapper {

    public enum DrumTarget {
        DAC_KICK(0, 3), DAC_SNARE(1, 2), DAC_TOM(2, 1),
        NOISE_SHORT(-1, 0), NOISE_LONG(-1, 0), DROP(-1, 0);

        public final int dacSlot;     // -1 = not a DAC target
        public final int priority;    // for simultaneous-hit resolution

        DrumTarget(int dacSlot, int priority) {
            this.dacSlot = dacSlot;
            this.priority = priority;
        }

        public boolean isDac() { return dacSlot >= 0; }
        public boolean isNoise() { return this == NOISE_SHORT || this == NOISE_LONG; }
    }

    /** Editable pitch → target table (dialog mutates a copy of the default). */
    public record Mapping(Map<Integer, DrumTarget> byPitch) {
        public DrumTarget targetFor(int pitch) {
            return byPitch.getOrDefault(pitch, DrumTarget.DROP);
        }
    }

    public record DrumHit(int startStep, int lengthSteps, DrumTarget target) {}

    public record SplitResult(List<DrumHit> dacHits, List<DrumHit> noiseHits,
                              List<Integer> droppedPitches) {}

    private GmDrumMapper() {}

    public static Mapping defaultMapping() {
        Map<Integer, DrumTarget> m = new HashMap<>();
        m.put(35, DrumTarget.DAC_KICK);   m.put(36, DrumTarget.DAC_KICK);
        m.put(38, DrumTarget.DAC_SNARE);  m.put(40, DrumTarget.DAC_SNARE);
        for (int tom : new int[]{41, 43, 45, 47, 48, 50}) m.put(tom, DrumTarget.DAC_TOM);
        m.put(42, DrumTarget.NOISE_SHORT); m.put(44, DrumTarget.NOISE_SHORT);
        m.put(46, DrumTarget.NOISE_LONG);
        for (int cym : new int[]{49, 51, 55, 57, 59}) m.put(cym, DrumTarget.NOISE_LONG);
        return new Mapping(m);
    }

    public static SplitResult split(List<NoteQuantizer.QuantizedNote> notes, Mapping mapping) {
        // step → best DAC hit (priority resolution)
        Map<Integer, DrumHit> dacByStep = new TreeMap<>();
        List<DrumHit> noise = new ArrayList<>();
        Set<Integer> dropped = new TreeSet<>();

        for (NoteQuantizer.QuantizedNote n : notes) {
            DrumTarget t = mapping.targetFor(n.pitch());
            if (t == DrumTarget.DROP) {
                dropped.add(n.pitch());
            } else if (t.isDac()) {
                DrumHit hit = new DrumHit(n.startStep(), n.lengthSteps(), t);
                dacByStep.merge(n.startStep(), hit,
                        (a, b) -> a.target().priority >= b.target().priority ? a : b);
            } else {
                noise.add(new DrumHit(n.startStep(),
                        t == DrumTarget.NOISE_SHORT ? 1 : n.lengthSteps(), t));
            }
        }
        noise.sort(Comparator.comparingInt(DrumHit::startStep));
        return new SplitResult(List.copyOf(dacByStep.values()), List.copyOf(noise),
                List.copyOf(dropped));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestGmDrumMapper`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/midi/GmDrumMapper.java app/src/test/java/com/opensmpsdeck/io/midi/TestGmDrumMapper.java
git commit -m "feat: GM drum pitch mapping to DAC slots and PSG noise"
```

---

### Task 6: MidiPhraseEncoder (quantized line → deduplicated phrases)

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/MidiPhraseEncoder.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/midi/TestMidiPhraseEncoder.java`

**Bytecode emission rules (verify with smps-bytecode-helper agent or SmpsDecoder if in doubt):**
- Note byte = `0x81 + pitch − 12 + 12*octaveShift`, clamped into 0x81–0xDF (clamps recorded as warnings)
- A note of `s` steps emits `[noteByte, dur]` where `dur = s × unitsPerSixteenth`; if `dur > 0x7F` it splits into chunks joined with `TIE` (`E7 noteByte dur` per continuation — E7 suppresses the re-attack of the following note)
- Gaps emit `[0x80, dur]` rests (same chunking, no tie needed between rests)
- The first event in every phrase carries an explicit duration; later events omit the duration byte when it repeats the previous one (SMPS "last duration" rule, as `SmpsEncoder` does)
- DAC hits: note byte = `0x81 + dacSlot`; noise hits use note byte `0xB0` (frequency is set by the noise mode, not the note — `PSG_NOISE` mode byte `F3 E4`, white noise / high rate, is emitted once at the start of the noise channel's first phrase and user-editable afterwards)

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.codec.SmpsDecoder;
import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.PhraseLibrary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestMidiPhraseEncoder {

    private static final MidiPhraseEncoder.EncodeParams P =
            new MidiPhraseEncoder.EncodeParams(4, 16, 1, 0); // 4 units/16th, 16 steps/bar, 1 bar/phrase, no shift

    @Test
    void encodesNoteAndRest() {
        var lib = new PhraseLibrary();
        var notes = List.of(new NoteQuantizer.QuantizedNote(0, 2, 60, 100)); // C4 half of the bar? 2 steps
        var warnings = new ArrayList<String>();
        var entries = MidiPhraseEncoder.encodeLine(notes, ChannelType.FM, P, lib,
                new java.util.HashMap<>(), "Synth-0", warnings);

        assertEquals(1, entries.size());
        byte[] data = lib.getPhrase(entries.get(0).getPhraseId()).getData();
        var rows = SmpsDecoder.decode(data);
        // row 0: C-4 (0xB1) dur 8 (2 steps × 4 units); row 1: rest filling the bar
        assertEquals("C-4", rows.get(0).note());
        assertEquals(8, rows.get(0).duration());
        assertEquals("---", rows.get(1).note());
        assertEquals(56, rows.get(1).duration()); // 14 steps × 4 units
        assertTrue(warnings.isEmpty());
    }

    @Test
    void identicalBarsDeduplicate() {
        var lib = new PhraseLibrary();
        // same one-note figure in bar 0 and bar 1
        var notes = List.of(
                new NoteQuantizer.QuantizedNote(0, 4, 60, 100),
                new NoteQuantizer.QuantizedNote(16, 4, 60, 100));
        var entries = MidiPhraseEncoder.encodeLine(notes, ChannelType.FM, P, lib,
                new java.util.HashMap<>(), "x", new ArrayList<>());
        // dedup collapses to one phrase id; consecutive repeat → one entry repeatCount 2
        assertEquals(1, entries.size());
        assertEquals(2, entries.get(0).getRepeatCount());
        assertEquals(1, lib.getAllPhrases().size());
    }

    @Test
    void longDurationSplitsWithTie() {
        // 40 steps at 4 units = 160 units > 0x7F → must split
        var p = new MidiPhraseEncoder.EncodeParams(4, 64, 1, 0); // long bar so no phrase cut
        var lib = new PhraseLibrary();
        var notes = List.of(new NoteQuantizer.QuantizedNote(0, 40, 60, 100));
        var entries = MidiPhraseEncoder.encodeLine(notes, ChannelType.FM, p, lib,
                new java.util.HashMap<>(), "x", new ArrayList<>());
        byte[] data = lib.getPhrase(entries.get(0).getPhraseId()).getData();
        // expect note 0x7F then E7-continued remainder; total duration preserved
        int total = SmpsDecoder.decode(data).stream()
                .filter(r -> !r.note().isEmpty())
                .mapToInt(r -> r.duration()).sum();
        assertEquals(160 + (64 - 40) * 4, total); // note chunks + trailing rest
    }

    @Test
    void outOfRangePitchClampsWithWarning() {
        var lib = new PhraseLibrary();
        var notes = List.of(new NoteQuantizer.QuantizedNote(0, 1, 5, 100)); // below MIDI 12
        var warnings = new ArrayList<String>();
        MidiPhraseEncoder.encodeLine(notes, ChannelType.FM, P, lib,
                new java.util.HashMap<>(), "x", warnings);
        assertFalse(warnings.isEmpty());
    }

    @Test
    void noteSpanningBarBoundarySplitsWithTie() {
        var lib = new PhraseLibrary();
        // starts at step 14, length 4 → crosses the bar at step 16
        var notes = List.of(new NoteQuantizer.QuantizedNote(14, 4, 60, 100));
        var entries = MidiPhraseEncoder.encodeLine(notes, ChannelType.FM, P, lib,
                new java.util.HashMap<>(), "x", new ArrayList<>());
        assertEquals(2, entries.size()); // two bars → two phrases
        // second phrase starts with a tied continuation of C-4
        byte[] second = lib.getPhrase(entries.get(1).getPhraseId()).getData();
        assertEquals((byte) 0xE7, second[0]);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestMidiPhraseEncoder`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.io.midi;

import com.opensmps.smps.SmpsCoordFlags;
import com.opensmpsdeck.model.ChainEntry;
import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.PhraseLibrary;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Encodes a quantized monophonic line into bar-aligned, deduplicated phrases.
 * Returns the chain entries referencing them (consecutive duplicates collapsed
 * into repeatCount).
 */
public final class MidiPhraseEncoder {

    /** unitsPerSixteenth from TempoFit; stepsPerBar from time signature (16 * num/den * 4/4). */
    public record EncodeParams(int unitsPerSixteenth, int stepsPerBar, int barsPerPhrase,
                               int octaveShift) {
        public int stepsPerPhrase() { return stepsPerBar * barsPerPhrase; }
    }

    static final int NOTE_BASE = 0x81;
    static final int NOTE_MAX = 0xDF;
    static final int REST = 0x80;
    static final int MAX_DURATION = 0x7F;
    static final int NOISE_NOTE = 0xB0;

    private MidiPhraseEncoder() {}

    /**
     * @param dedupIndex shared map "channelType:hexBytes" → phraseId, passed across
     *                   calls so dedup spans all lines of a channel type
     */
    public static List<ChainEntry> encodeLine(List<NoteQuantizer.QuantizedNote> notes,
                                              ChannelType type, EncodeParams p,
                                              PhraseLibrary library,
                                              Map<String, Integer> dedupIndex,
                                              String namePrefix, List<String> warnings) {
        int totalSteps = notes.stream()
                .mapToInt(n -> n.startStep() + n.lengthSteps()).max().orElse(0);
        int phraseSteps = p.stepsPerPhrase();
        int phraseCount = Math.max(1, (totalSteps + phraseSteps - 1) / phraseSteps);

        // step → pitch sounding at that step (already monophonic), -1 = silent,
        // attackStep marks where each note begins so continuations become ties
        int[] pitchAt = new int[phraseCount * phraseSteps];
        boolean[] attackAt = new boolean[pitchAt.length];
        Arrays.fill(pitchAt, -1);
        for (NoteQuantizer.QuantizedNote n : notes) {
            for (int s = n.startStep(); s < n.startStep() + n.lengthSteps()
                    && s < pitchAt.length; s++) {
                pitchAt[s] = n.pitch();
            }
            if (n.startStep() < attackAt.length) attackAt[n.startStep()] = true;
        }

        List<ChainEntry> entries = new ArrayList<>();
        for (int ph = 0; ph < phraseCount; ph++) {
            byte[] data = encodePhrase(pitchAt, attackAt, ph * phraseSteps,
                    Math.min((ph + 1) * phraseSteps, pitchAt.length), p, type, warnings);
            int phraseId = dedupOrCreate(data, type, library, dedupIndex,
                    namePrefix + "-" + String.format("%02d", ph));
            // collapse consecutive identical entries into repeatCount
            if (!entries.isEmpty()
                    && entries.get(entries.size() - 1).getPhraseId() == phraseId) {
                ChainEntry last = entries.get(entries.size() - 1);
                last.setRepeatCount(last.getRepeatCount() + 1);
            } else {
                entries.add(new ChainEntry(phraseId));
            }
        }
        return entries;
    }

    private static byte[] encodePhrase(int[] pitchAt, boolean[] attackAt, int from, int to,
                                       EncodeParams p, ChannelType type, List<String> warnings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int lastDuration = -1;
        int s = from;
        while (s < to) {
            int pitch = pitchAt[s];
            boolean tied = pitch >= 0 && !attackAt[s]; // continuation from previous bar/chunk
            int run = 1;
            while (s + run < to && pitchAt[s + run] == pitch
                    && (pitch < 0 || !attackAt[s + run])) {
                run++;
            }
            int durUnits = run * p.unitsPerSixteenth();
            int noteByte = pitch < 0 ? REST : toNoteByte(pitch, p.octaveShift(), type, warnings);
            lastDuration = emitChunked(out, noteByte, durUnits, tied, lastDuration);
            s += run;
        }
        return out.toByteArray();
    }

    /** Emits note/rest with duration, splitting >0x7F chunks with TIE. Returns new lastDuration. */
    private static int emitChunked(ByteArrayOutputStream out, int noteByte, int durUnits,
                                   boolean tied, int lastDuration) {
        boolean first = true;
        while (durUnits > 0) {
            int chunk = Math.min(durUnits, MAX_DURATION);
            boolean needTie = noteByte != REST && (tied || !first);
            if (needTie) out.write(SmpsCoordFlags.TIE);
            out.write(noteByte);
            if (chunk != lastDuration) {
                out.write(chunk);
                lastDuration = chunk;
            }
            durUnits -= chunk;
            first = false;
        }
        return lastDuration;
    }

    private static int toNoteByte(int pitch, int octaveShift, ChannelType type,
                                  List<String> warnings) {
        int nb = NOTE_BASE + pitch - 12 + 12 * octaveShift;
        if (nb < NOTE_BASE || nb > NOTE_MAX) {
            int clamped = Math.floorMod(nb - NOTE_BASE, 12) + NOTE_BASE
                    + (nb < NOTE_BASE ? 0 : (NOTE_MAX - NOTE_BASE) / 12 * 12);
            warnings.add(String.format("%s: MIDI pitch %d out of SMPS range, clamped", type, pitch));
            nb = Math.max(NOTE_BASE, Math.min(NOTE_MAX, clamped));
        }
        return nb;
    }

    private static int dedupOrCreate(byte[] data, ChannelType type, PhraseLibrary library,
                                     Map<String, Integer> dedupIndex, String name) {
        String key = type.name() + ":" + java.util.HexFormat.of().formatHex(data);
        Integer existing = dedupIndex.get(key);
        if (existing != null) return existing;
        Phrase phrase = library.createPhrase(name, type);
        phrase.setData(data);
        dedupIndex.put(key, phrase.getId());
        return phrase.getId();
    }
}
```

Note: if the project already exposes `HexUtil` (CLAUDE.md says use it for hex), replace `java.util.HexFormat.of().formatHex(data)` with the `HexUtil` equivalent — check `com.opensmpsdeck.io.HexUtil` for the encode method name and use it.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestMidiPhraseEncoder`
Expected: PASS (5 tests). If the tie-row assertions fail, check `SmpsDecoder.decode` semantics for `E7` (tie before note vs tie-as-row) and adjust `emitChunked` so a decoded tied note does not produce a re-attack row — `SmpsDecoder`/`SmpsSequencer` are authoritative.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/midi/MidiPhraseEncoder.java app/src/test/java/com/opensmpsdeck/io/midi/TestMidiPhraseEncoder.java
git commit -m "feat: encode quantized MIDI lines into deduplicated SMPS phrases"
```

---

### Task 7: GmVoiceSuggestions (curated seed voices)

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/GmVoiceSuggestions.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/midi/TestGmVoiceSuggestions.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestGmVoiceSuggestions {

    @Test
    void everyGmProgramReturnsAValidVoice() {
        for (int prog = 0; prog < 128; prog++) {
            FmVoice v = GmVoiceSuggestions.forProgram(prog);
            assertNotNull(v);
            assertEquals(FmVoice.VOICE_SIZE, v.getData().length);
        }
    }

    @Test
    void leadProgramsGetTheSquareLead() {
        assertEquals("GM Square Lead", GmVoiceSuggestions.forProgram(80).getName());
    }

    @Test
    void bassProgramsGetTheBass() {
        assertEquals("GM FM Bass", GmVoiceSuggestions.forProgram(32).getName());
    }

    @Test
    void seedBankHasDistinctVoices() {
        var bank = GmVoiceSuggestions.seedBank();
        assertTrue(bank.size() >= 4);
        long distinct = bank.stream().map(FmVoice::getName).distinct().count();
        assertEquals(bank.size(), distinct);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestGmVoiceSuggestions`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

Voices are built parametrically through `FmVoice` setters (never raw byte offsets). Parameter choices are conventional FM patch shapes; they are starting points the user replaces or that Phase 2's matcher refines.

```java
package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.FmVoice;

import java.util.List;

/** Curated FM voices suggested per GM program family. */
public final class GmVoiceSuggestions {

    private GmVoiceSuggestions() {}

    public static FmVoice forProgram(int program) {
        if (program >= 32 && program <= 39) return fmBass();      // basses
        if (program >= 80 && program <= 87) return squareLead();  // synth leads
        if (program >= 56 && program <= 63) return brass();       // brass
        if (program >= 8 && program <= 15) return bell();         // chromatic perc
        if (program >= 88 && program <= 95) return pad();         // pads
        return squareLead();                                       // default
    }

    public static List<FmVoice> seedBank() {
        return List.of(squareLead(), fmBass(), brass(), bell(), pad());
    }

    private static FmVoice base(String name) {
        return new FmVoice(name, new byte[FmVoice.VOICE_SIZE]);
    }

    // Preset factories are public: Phase 2 (audio.match) uses them as GA seeds
    // and ground-truth voices in its tests.

    /** Algorithm 4 (two stacked pairs), hollow square-ish lead. */
    public static FmVoice squareLead() {
        FmVoice v = base("GM Square Lead");
        v.setAlgorithm(4); v.setFeedback(3);
        for (int op = 0; op < 4; op++) {
            v.setAr(op, 31); v.setD1r(op, 6); v.setD2r(op, 2);
            v.setD1l(op, 1); v.setRr(op, 8);
        }
        v.setMul(0, 2); v.setTl(0, 30);   // modulator pair A
        v.setMul(1, 1); v.setTl(1, 8);    // carrier A
        v.setMul(2, 6); v.setTl(2, 45);   // modulator pair B (odd-harmonic colour)
        v.setMul(3, 2); v.setTl(3, 12);   // carrier B
        return v;
    }

    /** Algorithm 0 (serial chain), punchy bass. */
    public static FmVoice fmBass() {
        FmVoice v = base("GM FM Bass");
        v.setAlgorithm(0); v.setFeedback(5);
        for (int op = 0; op < 4; op++) {
            v.setAr(op, 31); v.setD1r(op, 12); v.setD2r(op, 4);
            v.setD1l(op, 2); v.setRr(op, 10);
        }
        v.setMul(0, 1); v.setTl(0, 28);
        v.setMul(1, 1); v.setTl(1, 35);
        v.setMul(2, 0); v.setTl(2, 40);   // MUL 0 = ×0.5: sub-octave weight
        v.setMul(3, 1); v.setTl(3, 6);    // carrier
        return v;
    }

    /** Algorithm 4, slower attack, brassy. */
    public static FmVoice brass() {
        FmVoice v = base("GM Brass");
        v.setAlgorithm(4); v.setFeedback(6);
        for (int op = 0; op < 4; op++) {
            v.setAr(op, 18); v.setD1r(op, 8); v.setD2r(op, 0);
            v.setD1l(op, 1); v.setRr(op, 8);
        }
        v.setMul(0, 1); v.setTl(0, 26);
        v.setMul(1, 1); v.setTl(1, 10);
        v.setMul(2, 1); v.setTl(2, 30);
        v.setMul(3, 2); v.setTl(3, 12);
        return v;
    }

    /** Algorithm 4, high-ratio modulators, fast decay: bell/keys. */
    public static FmVoice bell() {
        FmVoice v = base("GM Bell");
        v.setAlgorithm(4); v.setFeedback(2);
        for (int op = 0; op < 4; op++) {
            v.setAr(op, 31); v.setD1r(op, 14); v.setD2r(op, 6);
            v.setD1l(op, 4); v.setRr(op, 6);
        }
        v.setMul(0, 7); v.setTl(0, 38);   // inharmonic shimmer
        v.setMul(1, 2); v.setTl(1, 10);
        v.setMul(2, 3); v.setTl(2, 42);
        v.setMul(3, 1); v.setTl(3, 14);
        return v;
    }

    /** Algorithm 7 (all carriers), slow attack pad. */
    public static FmVoice pad() {
        FmVoice v = base("GM Pad");
        v.setAlgorithm(7); v.setFeedback(0);
        int[] muls = {1, 2, 1, 4};
        int[] tls = {16, 24, 20, 36};
        for (int op = 0; op < 4; op++) {
            v.setAr(op, 12); v.setD1r(op, 4); v.setD2r(op, 0);
            v.setD1l(op, 0); v.setRr(op, 5);
            v.setMul(op, muls[op]); v.setTl(op, tls[op]);
        }
        return v;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestGmVoiceSuggestions`
Expected: PASS (4 tests)

- [ ] **Step 5: Audible sanity check (manual, optional but recommended)**

Run the app (`mvn javafx:run -pl app` or the project's usual run config), add each seed voice via the FM voice editor, and preview. Tweak TL/AR values if a voice is silent or harsh — the carrier mask depends on algorithm (`FmVoice.isCarrier`), and a TL near 0 on a modulator can scream.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/midi/GmVoiceSuggestions.java app/src/test/java/com/opensmpsdeck/io/midi/TestGmVoiceSuggestions.java
git commit -m "feat: curated GM-program FM voice suggestions"
```

---

### Task 8: MidiImportSpec + MidiSongBuilder

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/MidiImportSpec.java`
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/MidiSongBuilder.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/midi/TestMidiSongBuilder.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.io.midi;

import com.opensmps.smps.SmpsCoordFlags;
import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.codec.SmpsDecoder;
import com.opensmpsdeck.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestMidiSongBuilder {

    private MidiImportSpec basicSpec() {
        var line = new VoiceSeparator.SeparatedLine(0, List.of(
                new NoteEvent(0, 480, 60, 100),
                new NoteEvent(480, 480, 64, 100)));
        var assignment = new MidiImportSpec.LineAssignment(
                "Synth", line, 0 /* FM1 */, 0 /* octave shift */,
                GmVoiceSuggestions.squareLead(), -1 /* no PSG env */);
        return new MidiImportSpec(
                "Like We", SmpsMode.S2, 0xCC, 2, 4 /* unitsPerSixteenth */,
                16 /* stepsPerBar */, 4 /* barsPerPhrase */, true /* loop */,
                480 /* ppq */, List.of(assignment),
                List.of() /* drum hits */, List.of(),
                GmDrumMapper.defaultMapping(), Map.of());
    }

    @Test
    void buildsSongWithAssignedChannel() {
        Song song = MidiSongBuilder.build(basicSpec());
        assertEquals("Like We", song.getName());
        assertEquals(SmpsMode.S2, song.getSmpsMode());
        assertEquals(0xCC, song.getTempo());
        assertEquals(2, song.getDividingTiming());
        assertEquals(ArrangementMode.HIERARCHICAL, song.getArrangementMode());

        Chain fm1 = song.getHierarchicalArrangement().getChain(0);
        assertFalse(fm1.getEntries().isEmpty());
        assertTrue(song.getHierarchicalArrangement().getChain(1).getEntries().isEmpty());
    }

    @Test
    void firstPhraseStartsWithVoiceCommand() {
        Song song = MidiSongBuilder.build(basicSpec());
        Chain fm1 = song.getHierarchicalArrangement().getChain(0);
        int firstId = fm1.getEntries().get(0).getPhraseId();
        byte[] data = song.getHierarchicalArrangement().getPhraseLibrary()
                .getPhrase(firstId).getData();
        assertEquals((byte) SmpsCoordFlags.SET_VOICE, data[0]);
        assertEquals(0, data[1]); // voice index 0 in the bank
        assertEquals(1, song.getVoiceBank().size());
    }

    @Test
    void loopWholeSongSetsLoopEntry() {
        Song song = MidiSongBuilder.build(basicSpec());
        assertEquals(0, song.getHierarchicalArrangement().getChain(0).getLoopEntryIndex());
    }

    @Test
    void drumHitsCreateDacChainAndPlaceholderSamples() {
        var spec = basicSpec();
        var withDrums = new MidiImportSpec(
                spec.songName(), spec.mode(), spec.tempoByte(), spec.dividingTiming(),
                spec.unitsPerSixteenth(), spec.stepsPerBar(), spec.barsPerPhrase(),
                spec.loopWholeSong(), spec.ppq(), spec.lines(),
                List.of(new GmDrumMapper.DrumHit(0, 1, GmDrumMapper.DrumTarget.DAC_KICK),
                        new GmDrumMapper.DrumHit(2, 1, GmDrumMapper.DrumTarget.DAC_SNARE)),
                List.of(new GmDrumMapper.DrumHit(1, 1, GmDrumMapper.DrumTarget.NOISE_SHORT)),
                spec.drumMapping(), spec.dacSampleOverrides());
        Song song = MidiSongBuilder.build(withDrums);

        assertFalse(song.getHierarchicalArrangement().getChain(5).getEntries().isEmpty());
        assertFalse(song.getHierarchicalArrangement().getChain(9).getEntries().isEmpty());
        // placeholder samples for the two used DAC slots (kick=0, snare=1)
        assertEquals(2, song.getDacSamples().size());
        assertEquals("Kick", song.getDacSamples().get(0).getName());
        assertEquals("Snare", song.getDacSamples().get(1).getName());
    }

    @Test
    void builtSongCompiles() {
        Song song = MidiSongBuilder.build(basicSpec());
        byte[] smps = new PatternCompiler().compile(song);
        assertTrue(smps.length > 6);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestMidiSongBuilder`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

`MidiImportSpec.java` — the dialog's confirmed result, fully describing the build:

```java
package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.SmpsMode;

import java.util.List;
import java.util.Map;

/** Everything MidiSongBuilder needs, produced by MidiImportDialog. */
public record MidiImportSpec(
        String songName,
        SmpsMode mode,
        int tempoByte,
        int dividingTiming,
        int unitsPerSixteenth,
        int stepsPerBar,
        int barsPerPhrase,
        boolean loopWholeSong,
        int ppq,
        List<LineAssignment> lines,
        List<GmDrumMapper.DrumHit> dacHits,
        List<GmDrumMapper.DrumHit> noiseHits,
        GmDrumMapper.Mapping drumMapping,
        Map<Integer, DacSample> dacSampleOverrides) {  // slot → real sample (Phase 2 fills this)

    /**
     * @param targetChannel model channel index 0-8 (FM1-5, DAC handled separately, PSG1-3)
     * @param voice         FM voice for FM channels (null for PSG)
     * @param psgEnvelopeId 1-based envelope id for PSG channels, -1 = none
     */
    public record LineAssignment(String stemName, VoiceSeparator.SeparatedLine line,
                                 int targetChannel, int octaveShift,
                                 FmVoice voice, int psgEnvelopeId) {}
}
```

`MidiSongBuilder.java`:

```java
package com.opensmpsdeck.io.midi;

import com.opensmps.smps.SmpsCoordFlags;
import com.opensmpsdeck.model.*;

import java.io.ByteArrayOutputStream;
import java.util.*;

/** Assembles the final Song from a confirmed MidiImportSpec. */
public final class MidiSongBuilder {

    private static final int DAC_CHANNEL = 5;
    private static final int NOISE_CHANNEL = 9;
    private static final int DEFAULT_DAC_RATE = 0x0C;
    private static final int NOISE_MODE_BYTE = 0xE4; // white noise, high fixed rate
    private static final String[] DAC_SLOT_NAMES = {"Kick", "Snare", "Tom"};

    private MidiSongBuilder() {}

    public static Song build(MidiImportSpec spec) {
        Song song = new Song();
        song.setName(spec.songName());
        song.setSmpsMode(spec.mode());
        song.setTempo(spec.tempoByte());
        song.setDividingTiming(spec.dividingTiming());
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);

        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        PhraseLibrary lib = arr.getPhraseLibrary();
        Map<String, Integer> dedup = new HashMap<>();
        List<String> warnings = new ArrayList<>();

        // melodic lines
        for (MidiImportSpec.LineAssignment a : spec.lines()) {
            ChannelType type = ChannelType.fromChannelIndex(a.targetChannel());
            var params = new MidiPhraseEncoder.EncodeParams(
                    spec.unitsPerSixteenth(), spec.stepsPerBar(), spec.barsPerPhrase(),
                    a.octaveShift());
            var quantized = NoteQuantizer.quantize(a.line().notes(), spec.ppq());
            List<ChainEntry> entries = MidiPhraseEncoder.encodeLine(quantized, type,
                    params, lib, dedup,
                    a.stemName() + "-" + a.line().rank(), warnings);
            if (entries.isEmpty()) continue;

            prefixInstrument(lib, entries.get(0), a, song);
            Chain chain = arr.getChain(a.targetChannel());
            chain.getEntries().addAll(entries);
        }

        // drums
        buildDrumChannel(spec, spec.dacHits(), arr, lib, dedup, song, true);
        buildDrumChannel(spec, spec.noiseHits(), arr, lib, dedup, song, false);

        // loop points
        if (spec.loopWholeSong()) {
            for (Chain c : arr.getChains()) {
                if (!c.getEntries().isEmpty()) c.setLoopEntryIndex(0);
            }
        }
        return song;
    }

    /** Prepends EF/F5 to the first phrase of a channel; registers the voice in the bank. */
    private static void prefixInstrument(PhraseLibrary lib, ChainEntry firstEntry,
                                         MidiImportSpec.LineAssignment a, Song song) {
        byte[] prefix;
        ChannelType type = ChannelType.fromChannelIndex(a.targetChannel());
        if (type == ChannelType.FM && a.voice() != null) {
            int idx = song.getVoiceBank().indexOf(a.voice());
            if (idx < 0) { song.getVoiceBank().add(a.voice()); idx = song.getVoiceBank().size() - 1; }
            prefix = new byte[]{(byte) SmpsCoordFlags.SET_VOICE, (byte) idx};
        } else if (type == ChannelType.PSG_TONE && a.psgEnvelopeId() > 0) {
            prefix = new byte[]{(byte) SmpsCoordFlags.PSG_INSTRUMENT, (byte) a.psgEnvelopeId()};
        } else {
            return;
        }
        prependToPhrase(lib, firstEntry, prefix, type);
    }

    private static void prependToPhrase(PhraseLibrary lib, ChainEntry entry, byte[] prefix,
                                        ChannelType type) {
        Phrase original = lib.getPhrase(entry.getPhraseId());
        byte[] data = original.getData();
        byte[] combined = new byte[prefix.length + data.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(data, 0, combined, prefix.length, data.length);
        // a prefixed phrase is channel-specific: always a fresh phrase, never deduped
        Phrase prefixed = lib.createPhrase(original.getName() + "+ins", type);
        prefixed.setData(combined);
        entry.setPhraseId(prefixed.getId());
    }

    private static void buildDrumChannel(MidiImportSpec spec, List<GmDrumMapper.DrumHit> hits,
                                         HierarchicalArrangement arr, PhraseLibrary lib,
                                         Map<String, Integer> dedup, Song song, boolean dac) {
        if (hits.isEmpty()) return;
        int channel = dac ? DAC_CHANNEL : NOISE_CHANNEL;
        ChannelType type = ChannelType.fromChannelIndex(channel);

        // drum hits → pseudo quantized notes whose "pitch" yields the right note byte:
        // DAC: noteByte = 0x81 + slot → pitch = slot + 12; noise: fixed NOISE_NOTE
        List<NoteQuantizer.QuantizedNote> notes = new ArrayList<>();
        Set<Integer> usedSlots = new TreeSet<>();
        for (GmDrumMapper.DrumHit h : hits) {
            int pitch;
            if (dac) {
                usedSlots.add(h.target().dacSlot);
                pitch = h.target().dacSlot + 12;
            } else {
                pitch = MidiPhraseEncoder.NOISE_NOTE - 0x81 + 12;
            }
            notes.add(new NoteQuantizer.QuantizedNote(h.startStep(), h.lengthSteps(), pitch, 100));
        }
        // drum hits never sustain into each other: re-sort and clip overlaps
        notes.sort(Comparator.comparingInt(NoteQuantizer.QuantizedNote::startStep));

        var params = new MidiPhraseEncoder.EncodeParams(spec.unitsPerSixteenth(),
                spec.stepsPerBar(), spec.barsPerPhrase(), 0);
        // drum hits are already quantized step-space notes — encodeLine takes them directly
        List<ChainEntry> entries = MidiPhraseEncoder.encodeLine(notes, type, params, lib, dedup,
                dac ? "Drums-DAC" : "Drums-Noise", new ArrayList<>());
        if (entries.isEmpty()) return;

        if (!dac) {
            prependToPhrase(lib, entries.get(0),
                    new byte[]{(byte) SmpsCoordFlags.PSG_NOISE, (byte) NOISE_MODE_BYTE}, type);
        } else {
            for (int slot : usedSlots) {
                while (song.getDacSamples().size() <= slot) {
                    int s = song.getDacSamples().size();
                    DacSample override = spec.dacSampleOverrides().get(s);
                    song.getDacSamples().add(override != null ? override
                            : new DacSample(DAC_SLOT_NAMES[Math.min(s, 2)], new byte[0],
                                            DEFAULT_DAC_RATE));
                }
            }
        }
        arr.getChain(channel).getEntries().addAll(entries);
    }
}
```

Call-site consistency: `encodeLine` takes `List<QuantizedNote>` (Task 6). Melodic lines quantize via `NoteQuantizer.quantize(a.line().notes(), spec.ppq())` before the call; drum hits are constructed directly in step space and pass straight in.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestMidiSongBuilder`
Expected: PASS (5 tests)

- [ ] **Step 5: Run the full app test suite to catch regressions**

Run: `mvn test -pl app`
Expected: all tests pass

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/midi app/src/test/java/com/opensmpsdeck/io/midi
git commit -m "feat: MidiSongBuilder assembling imported Songs from import specs"
```

---

### Task 9: MidiImportDialog + suggested default mapping

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/midi/MappingSuggester.java`
- Create: `app/src/main/java/com/opensmpsdeck/ui/MidiImportDialog.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/midi/TestMappingSuggester.java`

The dialog itself follows the project's untested-dialog convention (like `VoiceImportDialog`); the auto-mapping logic is extracted into the testable `MappingSuggester`.

- [ ] **Step 1: Write the failing test for MappingSuggester**

```java
package com.opensmpsdeck.io.midi;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TestMappingSuggester {

    private static MidiStem stem(String name, boolean drum, int program, NoteEvent... notes) {
        return new MidiStem(name, 480, List.of(), new MidiStem.TimeSignature(4, 4),
                List.of(new MidiStem.MidiNoteTrack(drum, Set.of(program), List.of(notes))));
    }

    @Test
    void melodicLinesFillFmThenPsg() {
        // two 4-line stems = 8 lines; separation caps at 4 lines per track,
        // so suggestions fill FM1-5 then PSG1-3
        NoteEvent[] chordA = new NoteEvent[4];
        NoteEvent[] chordB = new NoteEvent[4];
        for (int i = 0; i < 4; i++) {
            chordA[i] = new NoteEvent(0, 480, 60 + i * 3, 100);
            chordB[i] = new NoteEvent(0, 480, 61 + i * 3, 100);
        }
        var suggestions = MappingSuggester.suggest(List.of(
                stem("SynthA", false, 80, chordA), stem("SynthB", false, 80, chordB)));
        var channels = suggestions.stream()
                .map(MappingSuggester.Suggestion::targetChannel).toList();
        assertEquals(List.of(0, 1, 2, 3, 4, 6, 7, 8), channels);
    }

    @Test
    void drumTracksAreExcludedFromMelodicSuggestions() {
        var s = MappingSuggester.suggest(List.of(
                stem("Drums", true, 118, new NoteEvent(0, 100, 36, 100))));
        assertTrue(s.isEmpty()); // drums route through GmDrumMapper, not line mapping
    }

    @Test
    void lowMonoLinesPreferFmOverPsg() {
        // bass stem (program 32) should land on an FM channel even when listed last
        var s = MappingSuggester.suggest(List.of(
                stem("Synth", false, 80,
                        new NoteEvent(0, 480, 70, 100), new NoteEvent(0, 480, 74, 100)),
                stem("Bass", false, 32, new NoteEvent(0, 480, 40, 100))));
        var bass = s.stream().filter(x -> x.stemName().equals("Bass")).findFirst().orElseThrow();
        assertTrue(bass.targetChannel() <= 4);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestMappingSuggester`
Expected: COMPILE ERROR

- [ ] **Step 3: Write MappingSuggester**

```java
package com.opensmpsdeck.io.midi;

import java.util.ArrayList;
import java.util.List;

/** Produces the dialog's pre-filled line → channel assignment. */
public final class MappingSuggester {

    public record Suggestion(String stemName, VoiceSeparator.SeparatedLine line,
                             int gmProgram, int targetChannel) {}

    private static final int[] MELODIC_CHANNELS = {0, 1, 2, 3, 4, 6, 7, 8};
    private static final int MAX_LINES_PER_TRACK = 4;
    private static final int CHORD_EPSILON_TICKS = 15;

    private MappingSuggester() {}

    public static List<Suggestion> suggest(List<MidiStem> stems) {
        record Candidate(String stem, VoiceSeparator.SeparatedLine line, int program,
                         double medianPitch, boolean bassLike) {}
        List<Candidate> candidates = new ArrayList<>();

        for (MidiStem stem : stems) {
            for (MidiStem.MidiNoteTrack track : stem.tracks()) {
                if (track.drumTrack()) continue; // routed via GmDrumMapper
                int program = track.programs().stream().findFirst().orElse(80);
                var sep = VoiceSeparator.separate(track.notes(), MAX_LINES_PER_TRACK,
                        CHORD_EPSILON_TICKS);
                for (var line : sep.lines()) {
                    double median = line.notes().stream()
                            .mapToInt(NoteEvent::pitch).sorted()
                            .skip(line.notes().size() / 2).findFirst().orElse(60);
                    boolean bassLike = (program >= 32 && program <= 39) || median < 48;
                    candidates.add(new Candidate(stem.name(), line, program, median, bassLike));
                }
            }
        }
        // bass-like first (they must get FM), then by line rank, then by note count desc
        candidates.sort((a, b) -> {
            if (a.bassLike() != b.bassLike()) return a.bassLike() ? -1 : 1;
            if (a.line().rank() != b.line().rank())
                return Integer.compare(a.line().rank(), b.line().rank());
            return Integer.compare(b.line().notes().size(), a.line().notes().size());
        });

        List<Suggestion> out = new ArrayList<>();
        int next = 0;
        for (Candidate c : candidates) {
            if (next >= MELODIC_CHANNELS.length) break; // overflow stays unmapped
            out.add(new Suggestion(c.stem(), c.line(), c.program(), MELODIC_CHANNELS[next++]));
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestMappingSuggester`
Expected: PASS (3 tests)

- [ ] **Step 5: Write MidiImportDialog**

Follow `VoiceImportDialog`'s structure (a `Dialog<T>` with `setResultConverter`). The dialog is ~250 lines of JavaFX; its responsibilities are display and editing of the suggestion list — all logic lives in the pipeline classes already built. Key structure:

```java
package com.opensmpsdeck.ui;

import com.opensmpsdeck.io.midi.*;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.SmpsMode;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.*;

/** Mapping/preview dialog for MIDI import. Result: a confirmed MidiImportSpec. */
public class MidiImportDialog extends Dialog<MidiImportSpec> {

    /** One editable row in the mapping grid. */
    public static final class LineRow {
        final String stemName;
        final VoiceSeparator.SeparatedLine line;
        final int gmProgram;
        int targetChannel;          // -1 = not imported
        int octaveShift = 0;
        FmVoice voice;              // pre-filled GM suggestion; replaceable

        LineRow(MappingSuggester.Suggestion s) {
            this.stemName = s.stemName();
            this.line = s.line();
            this.gmProgram = s.gmProgram();
            this.targetChannel = s.targetChannel();
            this.voice = GmVoiceSuggestions.forProgram(s.gmProgram());
        }
    }

    private final ObservableList<LineRow> rows = FXCollections.observableArrayList();
    private final Spinner<Integer> phraseBars = new Spinner<>(1, 16, 4);
    private final ComboBox<SmpsMode> modeBox = new ComboBox<>(
            FXCollections.observableArrayList(SmpsMode.values()));
    private final CheckBox loopSong = new CheckBox("Loop whole song");
    private final Label tempoLabel = new Label();
    private final TextArea warningsArea = new TextArea();
    private final List<MidiStem> stems;
    private TempoFitter.TempoFit fit;
    private GmDrumMapper.SplitResult drumSplit;
    private final GmDrumMapper.Mapping drumMapping = GmDrumMapper.defaultMapping();

    public MidiImportDialog(List<MidiStem> stems) {
        this.stems = stems;
        setTitle("Import MIDI");
        modeBox.setValue(SmpsMode.S2);
        loopSong.setSelected(true);

        for (var s : MappingSuggester.suggest(stems)) rows.add(new LineRow(s));
        recomputeTempoAndDrums();
        modeBox.valueProperty().addListener((o, a, b) -> recomputeTempoAndDrums());

        VBox content = new VBox(10,
                new Label("Files: " + stems.size() + " stem(s)"),
                tempoLabel,
                buildMappingTable(),
                new HBox(10, new Label("Bars per phrase:"), phraseBars,
                        new Label("Mode:"), modeBox, loopSong),
                new Label("Warnings:"), warningsArea);
        content.setPadding(new Insets(10));
        warningsArea.setEditable(false);
        warningsArea.setPrefRowCount(4);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(bt -> bt == ButtonType.OK ? buildSpec() : null);
    }

    private void recomputeTempoAndDrums() {
        MidiStem first = stems.get(0);
        fit = TempoFitter.fit(first.tempoMap(), first.totalTicks(), first.ppq(),
                modeBox.getValue());
        tempoLabel.setText(String.format(
                "Tempo: %.1f BPM → tempo byte %02X, dividing timing %d, 16th = %d units (err %.2f%%)",
                fit.bpm(), fit.tempoByte(), fit.dividingTiming(),
                fit.unitsPerSixteenth(), fit.errorPercent()));

        List<NoteQuantizer.QuantizedNote> drumNotes = new ArrayList<>();
        StringBuilder warn = new StringBuilder();
        for (MidiStem stem : stems) {
            for (var track : stem.tracks()) {
                if (track.drumTrack()) {
                    drumNotes.addAll(NoteQuantizer.quantize(track.notes(), stem.ppq()));
                }
            }
        }
        drumSplit = GmDrumMapper.split(drumNotes, drumMapping);
        for (int p : drumSplit.droppedPitches()) {
            warn.append("Unmapped GM drum pitch ").append(p).append(" dropped\n");
        }
        warningsArea.setText(warn.toString());
    }

    private TableView<LineRow> buildMappingTable() {
        TableView<LineRow> table = new TableView<>(rows);
        table.setEditable(true);
        table.setPrefHeight(260);

        TableColumn<LineRow, String> stemCol = new TableColumn<>("Stem");
        stemCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                d.getValue().stemName + " line " + d.getValue().line.rank()));

        TableColumn<LineRow, String> notesCol = new TableColumn<>("Notes");
        notesCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(d.getValue().line.notes().size())));

        TableColumn<LineRow, String> channelCol = new TableColumn<>("Channel");
        channelCol.setCellFactory(c -> comboCell(
                List.of("—", "FM1", "FM2", "FM3", "FM4", "FM5", "PSG1", "PSG2", "PSG3"),
                row -> row.targetChannel < 0 ? "—" : channelName(row.targetChannel),
                (row, v) -> row.targetChannel = channelIndex(v)));

        TableColumn<LineRow, String> shiftCol = new TableColumn<>("Octave");
        shiftCol.setCellFactory(c -> comboCell(
                List.of("-2", "-1", "0", "+1", "+2"),
                row -> String.valueOf(row.octaveShift),
                (row, v) -> row.octaveShift = Integer.parseInt(v.replace("+", ""))));

        TableColumn<LineRow, String> voiceCol = new TableColumn<>("Instrument");
        voiceCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                d.getValue().voice != null ? d.getValue().voice.getName() : "(default)"));

        table.getColumns().addAll(List.of(stemCol, notesCol, channelCol, shiftCol, voiceCol));
        return table;
    }

    // comboCell helper: a TableCell with an editable ComboBox bound to the row via
    // the provided getter/setter lambdas — implement as a small private static method.

    private static String channelName(int ch) {
        return ch <= 4 ? "FM" + (ch + 1) : "PSG" + (ch - 5);
    }

    private static int channelIndex(String name) {
        if (name.equals("—")) return -1;
        return name.startsWith("FM") ? Integer.parseInt(name.substring(2)) - 1
                                     : Integer.parseInt(name.substring(3)) + 5;
    }

    private MidiImportSpec buildSpec() {
        List<MidiImportSpec.LineAssignment> assignments = new ArrayList<>();
        for (LineRow r : rows) {
            if (r.targetChannel < 0) continue;
            assignments.add(new MidiImportSpec.LineAssignment(
                    r.stemName, r.line, r.targetChannel, r.octaveShift, r.voice, -1));
        }
        MidiStem first = stems.get(0);
        // 16 sixteenth-steps per 4/4 bar, scaled by the time signature
        int stepsPerBar = 16 * first.timeSignature().numerator()
                / first.timeSignature().denominator();
        return new MidiImportSpec(
                commonPrefix(stems), modeBox.getValue(), fit.tempoByte(), fit.dividingTiming(),
                fit.unitsPerSixteenth(), stepsPerBar, phraseBars.getValue(),
                loopSong.isSelected(), first.ppq(), assignments,
                drumSplit.dacHits(), drumSplit.noiseHits(), drumMapping, Map.of());
    }

    private static String commonPrefix(List<MidiStem> stems) {
        String name = stems.get(0).name();
        int paren = name.indexOf(" (");
        return paren > 0 ? name.substring(0, paren) : name;
    }
}
```

Implement the `comboCell` helper (a `TableCell` housing a `ComboBox` whose value changes write through the setter lambda and call `table.refresh()`); follow whatever editable-cell pattern exists in `ChainEditor`/`OrderListPanel` if one is already established.

- [ ] **Step 6: Compile check**

Run: `mvn compile -pl app`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/midi/MappingSuggester.java app/src/main/java/com/opensmpsdeck/ui/MidiImportDialog.java app/src/test/java/com/opensmpsdeck/io/midi/TestMappingSuggester.java
git commit -m "feat: MIDI import mapping dialog with auto-suggested assignments"
```

---

### Task 10: Menu wiring in MainWindowFileActions

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindowFileActions.java` (add `onImportMidi()`, pattern of `onImportSmps()` at lines ~243-276)
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java` (menu item next to "Import SMPS..." at lines ~311-363)

- [ ] **Step 1: Add the action**

In `MainWindowFileActions`, mirror `onImportSmps()`:

```java
void onImportMidi() {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Import MIDI Stems");
    fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("MIDI Files", "*.mid", "*.midi"));
    List<File> files = fileChooser.showOpenMultipleDialog(stage);
    if (files == null || files.isEmpty()) return;

    List<MidiStem> stems = new ArrayList<>();
    StringBuilder errors = new StringBuilder();
    for (File f : files) {
        try {
            stems.add(MidiReader.read(f));
        } catch (IOException e) {
            errors.append(f.getName()).append(": ").append(e.getMessage()).append('\n');
        }
    }
    if (stems.isEmpty()) {
        new Alert(Alert.AlertType.ERROR, "No readable MIDI files:\n" + errors).showAndWait();
        return;
    }
    if (!errors.isEmpty()) {
        new Alert(Alert.AlertType.WARNING, "Some files were skipped:\n" + errors).showAndWait();
    }

    MidiImportDialog dialog = new MidiImportDialog(stems);
    Optional<MidiImportSpec> spec = dialog.showAndWait();
    if (spec.isEmpty()) return;

    Song song = MidiSongBuilder.build(spec.get());
    SongTab songTab = new SongTab(song);
    addTabConsumer.accept(songTab);
}
```

Add the needed imports (`com.opensmpsdeck.io.midi.*`, `MidiImportDialog`). Check how `stage` and `addTabConsumer` are named in the actual file and match them.

- [ ] **Step 2: Add the menu item**

In `MainWindow.createMenuBar()`, directly after the "Import SMPS..." item:

```java
MenuItem importMidiItem = new MenuItem("Import MIDI...");
importMidiItem.setOnAction(e -> fileActions.onImportMidi());
```

Add `importMidiItem` to the same menu list the SMPS item belongs to.

- [ ] **Step 3: Compile and run the suite**

Run: `mvn compile -pl app && mvn test -pl app`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 4: Manual smoke test**

Launch the app, File → Import MIDI…, select the five files in `C:\Users\farre\Downloads\Like We (Chiptune Remix) Stems`, confirm the dialog shows extracted lines with sane suggestions, click OK, and press play on the resulting song. Listen for: correct tempo feel (~110 BPM), bass on an FM channel, drums on DAC/noise.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/MainWindowFileActions.java app/src/main/java/com/opensmpsdeck/ui/MainWindow.java
git commit -m "feat: File > Import MIDI menu action"
```

---

### Task 11: Round-trip + full-stack integration tests

**Files:**
- Test: `app/src/test/java/com/opensmpsdeck/io/midi/TestMidiImportIntegration.java`

- [ ] **Step 1: Write the integration test**

```java
package com.opensmpsdeck.io.midi;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.model.Song;
import com.opensmpsdeck.model.SmpsMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.*;
import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestMidiImportIntegration {

    @TempDir
    File tempDir;

    /** Builds a tiny two-bar fixture: melody + bass + kick/hat drums at 120 BPM. */
    private File writeFixture() throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        Track meta = seq.createTrack();
        meta.add(new MidiEvent(new MetaMessage(0x51, new byte[]{0x07, (byte) 0xA1, 0x20}, 3), 0));

        Track melody = seq.createTrack();
        int[] pitches = {60, 64, 67, 72, 60, 64, 67, 72};
        for (int i = 0; i < pitches.length; i++) {
            melody.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitches[i], 100),
                    i * 480L));
            melody.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitches[i], 0),
                    i * 480L + 480));
        }
        Track drums = seq.createTrack();
        for (int beat = 0; beat < 8; beat++) {
            int pitch = beat % 2 == 0 ? 36 : 42; // kick / closed hat
            drums.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, pitch, 100),
                    beat * 480L));
            drums.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, pitch, 0),
                    beat * 480L + 120));
        }
        File f = new File(tempDir, "fixture.mid");
        MidiSystem.write(seq, 1, f);
        return f;
    }

    private Song importFixture() throws Exception {
        MidiStem stem = MidiReader.read(writeFixture());
        var suggestions = MappingSuggester.suggest(List.of(stem));
        var assignments = suggestions.stream()
                .map(s -> new MidiImportSpec.LineAssignment(s.stemName(), s.line(),
                        s.targetChannel(), 0, GmVoiceSuggestions.forProgram(s.gmProgram()), -1))
                .toList();

        var fit = TempoFitter.fit(stem.tempoMap(), stem.totalTicks(), stem.ppq(), SmpsMode.S2);
        var drumNotes = stem.tracks().stream()
                .filter(MidiStem.MidiNoteTrack::drumTrack)
                .flatMap(t -> NoteQuantizer.quantize(t.notes(), stem.ppq()).stream())
                .toList();
        var split = GmDrumMapper.split(drumNotes, GmDrumMapper.defaultMapping());

        return MidiSongBuilder.build(new MidiImportSpec(
                "Fixture", SmpsMode.S2, fit.tempoByte(), fit.dividingTiming(),
                fit.unitsPerSixteenth(), 16, 4, true, stem.ppq(), assignments,
                split.dacHits(), split.noiseHits(), GmDrumMapper.defaultMapping(), Map.of()));
    }

    @Test
    void importedSongHasMelodyDrumsAndLoop() throws Exception {
        Song song = importFixture();
        var arr = song.getHierarchicalArrangement();
        assertFalse(arr.getChain(0).getEntries().isEmpty(), "melody on FM1");
        assertFalse(arr.getChain(5).getEntries().isEmpty(), "kicks on DAC");
        assertFalse(arr.getChain(9).getEntries().isEmpty(), "hats on noise");
        assertEquals(0, arr.getChain(0).getLoopEntryIndex());
    }

    @Test
    void importedSongCompilesAndRendersAudio() throws Exception {
        Song song = importFixture();
        assertTrue(new PatternCompiler().compile(song).length > 6);

        PlaybackEngine engine = new PlaybackEngine();
        engine.loadSong(song);
        short[] buffer = new short[2048];
        long energy = 0;
        for (int i = 0; i < 100; i++) {           // ~2.3 s of audio
            engine.renderBuffer(buffer);
            for (short s : buffer) energy += Math.abs(s);
        }
        assertTrue(energy > 0, "rendered audio must not be silent");
    }
}
```

- [ ] **Step 2: Run test**

Run: `mvn test -pl app -Dtest=TestMidiImportIntegration`
Expected: PASS (2 tests). If the render is silent, the likely causes in order: voice TLs muting carriers (check `GmVoiceSuggestions`), missing `SET_VOICE` prefix (Task 8), or empty placeholder DAC samples making the DAC channel silent — that one is acceptable; melody must still produce energy.

- [ ] **Step 3: Run the entire suite for both modules**

Run: `mvn test`
Expected: all tests pass (407 pre-existing + new)

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/opensmpsdeck/io/midi/TestMidiImportIntegration.java
git commit -m "test: MIDI import round-trip and full-stack render integration"
```

---

## Self-Review Checklist (run after all tasks)

- [ ] Every spec section maps to a task: reader (T1), separation (T3), tempo (T4), drums (T5), encoding+dedup (T6), instruments (T7), build (T8), dialog (T9), wiring (T10), error handling (T1/T10), testing (all + T11)
- [ ] `MidiPhraseEncoder` tie emission verified against `SmpsDecoder` semantics (T6 step 4 note)
- [ ] `TempoMath` verified against `SmpsSequencer` (T4 step 1)
- [ ] Quantize call-site consistency between Task 6 and Task 8 resolved (T8 step 3 refactor note)
- [ ] CLAUDE.md updated: add `io.midi` package row to the architecture table and `MidiImportDialog` to the UI table
