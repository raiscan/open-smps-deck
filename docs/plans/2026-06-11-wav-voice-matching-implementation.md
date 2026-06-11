# WAV Voice Matching & Drum Sample Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Derive `FmVoice` candidates from stem WAVs by genetic search against the in-process `Ym2612Chip`, and slice drum one-shots from the Drums WAV into `DacSample`s.

**Architecture:** New package `com.opensmpsdeck.audio.match`: WavStemReader → MonophonicWindowFinder (MIDI-guided isolation) → SpectralTarget (FFT harmonics + RMS envelope) → CandidateRenderer (headless chip) → FmPatchSearch (seeded GA + hill-climb) behind an async VoiceMatchService; plus DrumSliceExtractor. UI: "Match from WAV…" in MidiImportDialog and FmVoiceEditor.

**Tech Stack:** Java 21, `javax.sound.sampled` (JDK), synth-core `Ym2612Chip`, JUnit 5. No new Maven dependencies.

**Spec:** `docs/plans/2026-06-11-wav-voice-matching-design.md`

**PREREQUISITE:** Phase 1 (`2026-06-11-midi-import-implementation.md`) must be implemented first — this plan uses `NoteEvent`, `TickTimeMapper`, `MidiStem`, `GmVoiceSuggestions.seedBank()`, and extends `MidiImportDialog`.

**Key chip facts (from synth-core):**
- `Ym2612Chip()` no-arg; `setOutputSampleRate(44100.0)`; `setInstrument(int chIdx, byte[] voice25)` handles SMPS→register operator reordering itself
- `write(int port, int reg, int val)`; key-on/off = reg `0x28` port 0, value `(keyMask << 4) | channel` — `0xF0` = all four operators, channel 0
- Frequency: write reg `0xA4` (block<<3 | fnum>>8) **before** `0xA0` (fnum low byte)
- Pan: reg `0xB4` must be set to `0xC0` (L+R) after reset or the channel is silent
- `renderStereo(int[] left, int[] right, int len)` **accumulates** into the arrays — clear them per chunk
- F-numbers (Z80 table, semitone C..B): `{644, 683, 723, 766, 813, 860, 911, 965, 1023, 1084, 1148, 1216}`

---

### Task 1: Fft utility

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/audio/match/Fft.java`
- Test: `app/src/test/java/com/opensmpsdeck/audio/match/TestFft.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.audio.match;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestFft {

    @Test
    void impulseHasFlatSpectrum() {
        double[] re = new double[64];
        double[] im = new double[64];
        re[0] = 1.0;
        Fft.transform(re, im);
        for (int i = 0; i < 64; i++) {
            assertEquals(1.0, Math.hypot(re[i], im[i]), 1e-9);
        }
    }

    @Test
    void sinePeaksAtItsBin() {
        int n = 1024;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) re[i] = Math.sin(2 * Math.PI * 16 * i / n); // bin 16
        Fft.transform(re, im);
        int peak = 0;
        double max = 0;
        for (int i = 1; i < n / 2; i++) {
            double mag = Math.hypot(re[i], im[i]);
            if (mag > max) { max = mag; peak = i; }
        }
        assertEquals(16, peak);
    }

    @Test
    void rejectsNonPowerOfTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> Fft.transform(new double[100], new double[100]));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestFft`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.audio.match;

/** In-place iterative radix-2 Cooley-Tukey FFT. */
public final class Fft {

    private Fft() {}

    public static void transform(double[] re, double[] im) {
        int n = re.length;
        if (n != im.length || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("length must be a power of two");
        }
        // bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wRe = Math.cos(ang), wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1, curIm = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double tRe = re[b] * curRe - im[b] * curIm;
                    double tIm = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - tRe; im[b] = im[a] - tIm;
                    re[a] += tRe;        im[a] += tIm;
                    double nRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nRe;
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestFft`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/match app/src/test/java/com/opensmpsdeck/audio/match
git commit -m "feat: radix-2 FFT utility for spectral matching"
```

---

### Task 2: WavStemReader

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/audio/match/WavStemReader.java`
- Test: `app/src/test/java/com/opensmpsdeck/audio/match/TestWavStemReader.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.audio.match;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class TestWavStemReader {

    @TempDir
    File tempDir;

    /** Writes a WAV of the given format containing a 440 Hz sine. */
    private File writeSineWav(float sampleRate, int channels) throws Exception {
        int frames = (int) sampleRate; // 1 second
        byte[] pcm = new byte[frames * channels * 2];
        for (int i = 0; i < frames; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 12000);
            for (int c = 0; c < channels; c++) {
                int off = (i * channels + c) * 2;
                pcm[off] = (byte) (v & 0xFF);
                pcm[off + 1] = (byte) (v >> 8);
            }
        }
        AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false);
        File f = new File(tempDir, "sine.wav");
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), fmt, frames),
                AudioFileFormat.Type.WAVE, f);
        return f;
    }

    @Test
    void readsStereo44kToMonoFloat() throws Exception {
        float[] mono = WavStemReader.readMono44k(writeSineWav(44100, 2));
        assertEquals(44100, mono.length, 100);
        double peak = 0;
        for (float v : mono) peak = Math.max(peak, Math.abs(v));
        assertEquals(12000.0 / 32768.0, peak, 0.02);
    }

    @Test
    void resamples48kTo44k() throws Exception {
        float[] mono = WavStemReader.readMono44k(writeSineWav(48000, 1));
        assertEquals(44100, mono.length, 200);
        // frequency must be preserved: count zero crossings ≈ 880/sec
        int crossings = 0;
        for (int i = 1; i < mono.length; i++) {
            if (mono[i - 1] < 0 != mono[i] < 0) crossings++;
        }
        assertEquals(880, crossings, 20);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestWavStemReader`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.audio.match;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;

/** Reads a WAV file as mono float samples at 44.1 kHz. */
public final class WavStemReader {

    public static final int TARGET_RATE = 44100;

    private WavStemReader() {}

    public static float[] readMono44k(File file) throws IOException {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(file)) {
            AudioFormat src = raw.getFormat();
            AudioFormat pcm16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(), 16, src.getChannels(),
                    src.getChannels() * 2, src.getSampleRate(), false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(pcm16, raw)) {
                byte[] bytes = in.readAllBytes();
                int channels = pcm16.getChannels();
                int frames = bytes.length / (channels * 2);
                float[] mono = new float[frames];
                for (int i = 0; i < frames; i++) {
                    int sum = 0;
                    for (int c = 0; c < channels; c++) {
                        int off = (i * channels + c) * 2;
                        sum += (short) ((bytes[off] & 0xFF) | (bytes[off + 1] << 8));
                    }
                    mono[i] = (float) sum / channels / 32768f;
                }
                return resample(mono, src.getSampleRate(), TARGET_RATE);
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported WAV encoding: " + file.getName(), e);
        }
    }

    /** Linear-interpolation resampler — adequate for analysis targets. */
    static float[] resample(float[] in, float fromRate, float toRate) {
        if (Math.abs(fromRate - toRate) < 0.5f) return in;
        int outLen = (int) ((long) in.length * toRate / fromRate);
        float[] out = new float[outLen];
        double step = fromRate / toRate;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = pos - i0;
            out[i] = (float) (in[i0] * (1 - frac) + in[i1] * frac);
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestWavStemReader`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/match/WavStemReader.java app/src/test/java/com/opensmpsdeck/audio/match/TestWavStemReader.java
git commit -m "feat: WAV stem reader with mono mixdown and 44.1k resample"
```

---

### Task 3: SpectralTarget

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/audio/match/SpectralTarget.java`
- Test: `app/src/test/java/com/opensmpsdeck/audio/match/TestSpectralTarget.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.audio.match;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSpectralTarget {

    private static float[] sine(double hz, double seconds, double amp) {
        int n = (int) (44100 * seconds);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) s[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / 44100));
        return s;
    }

    @Test
    void pureSineConcentratesInFirstHarmonic() {
        SpectralTarget t = SpectralTarget.extract(sine(440, 1.0, 0.5), 44100, 440);
        assertEquals(0.0, t.harmonicLevels()[0], 1.0);   // normalized peak = 0 dB
        for (int h = 1; h < t.harmonicLevels().length; h++) {
            assertTrue(t.harmonicLevels()[h] < -30, "harmonic " + (h + 1) + " should be silent");
        }
    }

    @Test
    void twoHarmonicSignalShowsExpectedRatio() {
        float[] sig = sine(440, 1.0, 0.5);
        float[] second = sine(880, 1.0, 0.25); // -6 dB relative
        for (int i = 0; i < sig.length; i++) sig[i] += second[i];
        SpectralTarget t = SpectralTarget.extract(sig, 44100, 440);
        assertEquals(-6.0, t.harmonicLevels()[1] - t.harmonicLevels()[0], 1.5);
    }

    @Test
    void envelopeTracksAmplitude() {
        // 0.5 s loud then 0.5 s quiet
        float[] sig = new float[44100];
        System.arraycopy(sine(440, 0.5, 0.8), 0, sig, 0, 22050);
        System.arraycopy(sine(440, 0.5, 0.1), 0, sig, 22050, 22050);
        SpectralTarget t = SpectralTarget.extract(sig, 44100, 440);
        double[] env = t.rmsEnvelope();
        assertEquals(1.0, env[env.length / 4], 0.1);     // first half ≈ peak
        assertTrue(env[3 * env.length / 4] < 0.25);      // second half quiet
    }

    @Test
    void distanceIsZeroForIdenticalTargets() {
        SpectralTarget a = SpectralTarget.extract(sine(440, 1.0, 0.5), 44100, 440);
        assertEquals(0.0, SpectralTarget.distance(a, a), 1e-9);
    }

    @Test
    void distanceGrowsWithSpectralDifference() {
        SpectralTarget pure = SpectralTarget.extract(sine(440, 1.0, 0.5), 44100, 440);
        float[] rich = sine(440, 1.0, 0.4);
        float[] h3 = sine(1320, 1.0, 0.3);
        for (int i = 0; i < rich.length; i++) rich[i] += h3[i];
        SpectralTarget bright = SpectralTarget.extract(rich, 44100, 440);
        assertTrue(SpectralTarget.distance(pure, bright) > SpectralTarget.distance(pure, pure));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestSpectralTarget`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.audio.match;

import java.util.Arrays;

/**
 * Timbre fingerprint of an audio slice with a known fundamental:
 * log-magnitude levels of the first 16 harmonics (dB, peak-normalized to 0)
 * plus a peak-normalized RMS envelope (10 ms hops, fixed 64 points).
 */
public record SpectralTarget(double[] harmonicLevels, double[] rmsEnvelope,
                             double fundamentalHz) {

    public static final int HARMONICS = 16;
    public static final int ENVELOPE_POINTS = 64;
    private static final int FFT_SIZE = 2048;
    private static final int HOP = 1024;
    private static final double ATTACK_SKIP_SEC = 0.05;
    private static final double DB_FLOOR = -60.0;
    private static final double PEAK_SEARCH = 0.03; // ±3% around each harmonic

    public static SpectralTarget extract(float[] audio, int sampleRate, double fundamentalHz) {
        // --- averaged magnitude spectrum over the sustain (post-attack) ---
        double[] avgMag = new double[FFT_SIZE / 2];
        int start = (int) (ATTACK_SKIP_SEC * sampleRate);
        int frameCount = 0;
        for (int off = start; off + FFT_SIZE <= audio.length; off += HOP) {
            double[] re = new double[FFT_SIZE];
            double[] im = new double[FFT_SIZE];
            for (int i = 0; i < FFT_SIZE; i++) {
                double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
                re[i] = audio[off + i] * hann;
            }
            Fft.transform(re, im);
            for (int i = 0; i < FFT_SIZE / 2; i++) avgMag[i] += Math.hypot(re[i], im[i]);
            frameCount++;
        }
        if (frameCount == 0) frameCount = 1;
        for (int i = 0; i < avgMag.length; i++) avgMag[i] /= frameCount;

        // --- harmonic levels with ±3% peak search ---
        double binHz = (double) sampleRate / FFT_SIZE;
        double[] levels = new double[HARMONICS];
        for (int h = 1; h <= HARMONICS; h++) {
            double targetHz = fundamentalHz * h;
            int lo = (int) Math.max(1, (targetHz * (1 - PEAK_SEARCH)) / binHz);
            int hi = (int) Math.min(avgMag.length - 1, (targetHz * (1 + PEAK_SEARCH)) / binHz);
            double peak = 0;
            for (int b = lo; b <= hi; b++) peak = Math.max(peak, avgMag[b]);
            levels[h - 1] = 20 * Math.log10(Math.max(peak, 1e-12));
        }
        double max = Arrays.stream(levels).max().orElse(0);
        for (int i = 0; i < levels.length; i++) {
            levels[i] = Math.max(levels[i] - max, DB_FLOOR);
        }

        // --- RMS envelope, resampled to a fixed point count ---
        int hopSamples = sampleRate / 100; // 10 ms
        int hops = Math.max(1, audio.length / hopSamples);
        double[] rms = new double[hops];
        double peakRms = 1e-12;
        for (int i = 0; i < hops; i++) {
            double sum = 0;
            int n = Math.min(hopSamples, audio.length - i * hopSamples);
            for (int j = 0; j < n; j++) {
                double v = audio[i * hopSamples + j];
                sum += v * v;
            }
            rms[i] = Math.sqrt(sum / Math.max(1, n));
            peakRms = Math.max(peakRms, rms[i]);
        }
        double[] envelope = new double[ENVELOPE_POINTS];
        for (int i = 0; i < ENVELOPE_POINTS; i++) {
            envelope[i] = rms[Math.min(hops - 1, i * hops / ENVELOPE_POINTS)] / peakRms;
        }
        return new SpectralTarget(levels, envelope, fundamentalHz);
    }

    /** Weighted distance: harmonic dB MSE (normalized) + envelope MSE. */
    public static double distance(SpectralTarget a, SpectralTarget b) {
        double spec = 0;
        for (int i = 0; i < HARMONICS; i++) {
            double d = a.harmonicLevels()[i] - b.harmonicLevels()[i];
            spec += d * d;
        }
        spec /= HARMONICS * DB_FLOOR * DB_FLOOR; // normalize to ~[0,1]
        double env = 0;
        for (int i = 0; i < ENVELOPE_POINTS; i++) {
            double d = a.rmsEnvelope()[i] - b.rmsEnvelope()[i];
            env += d * d;
        }
        env /= ENVELOPE_POINTS;
        return 0.7 * spec + 0.3 * env;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestSpectralTarget`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/match/SpectralTarget.java app/src/test/java/com/opensmpsdeck/audio/match/TestSpectralTarget.java
git commit -m "feat: spectral fingerprint extraction and distance metric"
```

---

### Task 4: MonophonicWindowFinder

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/audio/match/MonophonicWindowFinder.java`
- Test: `app/src/test/java/com/opensmpsdeck/audio/match/TestMonophonicWindowFinder.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.MidiStem;
import com.opensmpsdeck.io.midi.NoteEvent;
import com.opensmpsdeck.io.midi.TickTimeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestMonophonicWindowFinder {

    // 480 ppq at 120 BPM → 1 tick = 1/960 s; 480 ticks = 0.5 s
    private static final TickTimeMapper MAP = new TickTimeMapper(480,
            List.of(new MidiStem.TempoEvent(0, 500000)));

    @Test
    void findsIsolatedSustainedNote() {
        var notes = List.of(
                new NoteEvent(0, 480, 60, 100),       // isolated, 0.5 s — good
                new NoteEvent(960, 480, 64, 100),     // overlapped below
                new NoteEvent(960, 480, 67, 100));
        var windows = MonophonicWindowFinder.find(notes, MAP, 3);
        assertEquals(1, windows.size());
        assertEquals(60, windows.get(0).midiPitch());
        assertEquals(0.0, windows.get(0).startSec(), 1e-6);
        assertEquals(0.5, windows.get(0).lengthSec(), 1e-6);
    }

    @Test
    void shortNotesAreRejected() {
        // 96 ticks = 0.1 s < 250 ms minimum
        var windows = MonophonicWindowFinder.find(
                List.of(new NoteEvent(0, 96, 60, 100)), MAP, 3);
        assertTrue(windows.isEmpty());
    }

    @Test
    void ranksLongerLouderWindowsFirst() {
        var notes = List.of(
                new NoteEvent(0, 480, 60, 50),        // quiet
                new NoteEvent(1920, 960, 64, 120));   // long and loud → first
        var windows = MonophonicWindowFinder.find(notes, MAP, 3);
        assertEquals(64, windows.get(0).midiPitch());
    }

    @Test
    void drumModeRequiresTemporalIsolation() {
        var kicks = List.of(
                new NoteEvent(0, 48, 36, 100),
                new NoteEvent(960, 48, 36, 100));     // isolated from the other class
        var hats = List.of(new NoteEvent(30, 48, 42, 100)); // crowds the first kick
        var windows = MonophonicWindowFinder.findDrumHits(kicks, hats, MAP, 0.06);
        assertEquals(1, windows.size());
        assertEquals(1.0, windows.get(0).startSec(), 1e-3); // tick 960 at 120 BPM/480ppq = 1.0 s
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestMonophonicWindowFinder`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.NoteEvent;
import com.opensmpsdeck.io.midi.TickTimeMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds time windows where a stem plays exactly one note (or one isolated drum hit). */
public final class MonophonicWindowFinder {

    public record Window(double startSec, double lengthSec, int midiPitch, double score) {}

    private static final double MIN_LENGTH_SEC = 0.25;

    private MonophonicWindowFinder() {}

    /** Melodic mode: windows where exactly one note sounds for ≥ 250 ms. */
    public static List<Window> find(List<NoteEvent> notes, TickTimeMapper map, int topK) {
        List<Window> candidates = new ArrayList<>();
        for (NoteEvent n : notes) {
            boolean isolated = notes.stream().noneMatch(o -> o != n
                    && o.startTick() < n.endTick() && o.endTick() > n.startTick());
            if (!isolated) continue;
            double start = map.secondsAt(n.startTick());
            double len = map.secondsAt(n.endTick()) - start;
            if (len < MIN_LENGTH_SEC) continue;
            // isolation margin: gap to the nearest neighbouring note
            double margin = notes.stream()
                    .filter(o -> o != n)
                    .mapToDouble(o -> Math.min(
                            Math.abs(map.secondsAt(o.startTick()) - map.secondsAt(n.endTick())),
                            Math.abs(map.secondsAt(n.startTick()) - map.secondsAt(o.endTick()))))
                    .min().orElse(1.0);
            candidates.add(new Window(start, len, n.pitch(),
                    len * Math.min(margin, 1.0) * (n.velocity() / 127.0)));
        }
        candidates.sort(Comparator.comparingDouble(Window::score).reversed());
        return candidates.subList(0, Math.min(topK, candidates.size()));
    }

    /**
     * Drum mode: hits of one class with no hit of any other class within ±isolationSec.
     * Returns all isolated hits ranked by velocity (caller takes the loudest).
     */
    public static List<Window> findDrumHits(List<NoteEvent> classHits, List<NoteEvent> otherHits,
                                            TickTimeMapper map, double isolationSec) {
        List<Window> out = new ArrayList<>();
        for (NoteEvent n : classHits) {
            double start = map.secondsAt(n.startTick());
            boolean isolated = otherHits.stream().noneMatch(o ->
                    Math.abs(map.secondsAt(o.startTick()) - start) < isolationSec);
            if (isolated) {
                out.add(new Window(start, map.secondsAt(n.endTick()) - start, n.pitch(),
                        n.velocity() / 127.0));
            }
        }
        out.sort(Comparator.comparingDouble(Window::score).reversed());
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestMonophonicWindowFinder`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/match/MonophonicWindowFinder.java app/src/test/java/com/opensmpsdeck/audio/match/TestMonophonicWindowFinder.java
git commit -m "feat: MIDI-guided isolated-note window finder"
```

---

### Task 5: CandidateRenderer (headless chip render)

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/audio/match/CandidateRenderer.java`
- Test: `app/src/test/java/com/opensmpsdeck/audio/match/TestCandidateRenderer.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.GmVoiceSuggestions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestCandidateRenderer {

    @Test
    void rendersAudibleNote() {
        var r = new CandidateRenderer();
        float[] audio = r.render(GmVoiceSuggestions.squareLead().getData(), 60, 0.5, 0.25);
        assertEquals((int) (0.75 * 44100), audio.length, 100);
        double peak = 0;
        for (float v : audio) peak = Math.max(peak, Math.abs(v));
        assertTrue(peak > 0.01, "rendered note must be audible, peak was " + peak);
    }

    @Test
    void energyDecaysAfterKeyOff() {
        var r = new CandidateRenderer();
        float[] audio = r.render(GmVoiceSuggestions.squareLead().getData(), 60, 0.5, 0.5);
        double sustain = rms(audio, (int) (0.3 * 44100), (int) (0.5 * 44100));
        double tail = rms(audio, (int) (0.8 * 44100), audio.length);
        assertTrue(tail < sustain * 0.7, "tail " + tail + " vs sustain " + sustain);
    }

    @Test
    void renderIsRepeatable() {
        var r = new CandidateRenderer();
        byte[] v = GmVoiceSuggestions.fmBass().getData();
        float[] a = r.render(v, 48, 0.3, 0.1);
        float[] b = r.render(v, 48, 0.3, 0.1);
        assertArrayEquals(a, b, 1e-9f);
    }

    private static double rms(float[] a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += a[i] * a[i];
        return Math.sqrt(s / (to - from));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestCandidateRenderer`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.audio.match;

import com.opensmps.synth.Ym2612Chip;

/**
 * Renders an FM voice headlessly through the real YM2612 emulator:
 * key-on at the given MIDI pitch, sustain, key-off, tail.
 * One instance per thread (the chip is stateful).
 */
public final class CandidateRenderer {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHUNK = 512;
    private static final int CHANNEL = 0;
    // Z80 driver F-number table, semitone C..B
    private static final int[] FNUM = {644, 683, 723, 766, 813, 860, 911, 965,
                                       1023, 1084, 1148, 1216};

    private final Ym2612Chip chip = new Ym2612Chip();

    public CandidateRenderer() {
        chip.setOutputSampleRate(SAMPLE_RATE);
    }

    public float[] render(byte[] voice25, int midiPitch, double keyOnSec, double tailSec) {
        chip.reset();
        chip.setOutputSampleRate(SAMPLE_RATE);
        chip.setInstrument(CHANNEL, voice25);
        chip.write(0, 0xB4 + CHANNEL, 0xC0); // pan L+R (silent after reset otherwise)

        int semitone = Math.floorMod(midiPitch, 12);
        int block = Math.max(0, Math.min(7, midiPitch / 12 - 1));
        int fnum = FNUM[semitone];
        chip.write(0, 0xA4 + CHANNEL, (block << 3) | (fnum >> 8)); // high byte FIRST
        chip.write(0, 0xA0 + CHANNEL, fnum & 0xFF);

        int keyOnSamples = (int) (keyOnSec * SAMPLE_RATE);
        int tailSamples = (int) (tailSec * SAMPLE_RATE);
        float[] out = new float[keyOnSamples + tailSamples];

        chip.write(0, 0x28, 0xF0 | CHANNEL); // all four operators on
        renderInto(out, 0, keyOnSamples);
        chip.write(0, 0x28, CHANNEL);        // key off
        renderInto(out, keyOnSamples, tailSamples);
        return out;
    }

    private void renderInto(float[] out, int offset, int count) {
        int[] left = new int[CHUNK];
        int[] right = new int[CHUNK];
        int done = 0;
        while (done < count) {
            int n = Math.min(CHUNK, count - done);
            java.util.Arrays.fill(left, 0, n, 0);   // renderStereo accumulates
            java.util.Arrays.fill(right, 0, n, 0);
            chip.renderStereo(left, right, n);
            for (int i = 0; i < n; i++) {
                out[offset + done + i] = (left[i] + right[i]) / 2f / 32768f;
            }
            done += n;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestCandidateRenderer`
Expected: PASS (3 tests). If silent: check `0xB4` pan write, the `0x28` key-on value layout (`(keyMask << 4) | channel`), and whether `renderStereo`'s output scale matches the `/32768` normalization (inspect a few raw `left[]` values — internal clamp is ±8191; adjust the divisor so peak lands in (0,1]).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/match/CandidateRenderer.java app/src/test/java/com/opensmpsdeck/audio/match/TestCandidateRenderer.java
git commit -m "feat: headless YM2612 candidate voice renderer"
```

---

### Task 6: FmPatchSearch (seeded GA + hill-climb)

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/audio/match/FmPatchSearch.java`
- Test: `app/src/test/java/com/opensmpsdeck/audio/match/TestFmPatchSearch.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.GmVoiceSuggestions;
import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestFmPatchSearch {

    /** Ground truth: render a known voice, use it as the target, search must approach it. */
    @Test
    void recoversKnownVoiceSpectrally() {
        FmVoice truth = GmVoiceSuggestions.fmBass();
        var renderer = new CandidateRenderer();
        float[] audio = renderer.render(truth.getData(), 48, 0.5, 0.25);
        SpectralTarget target = SpectralTarget.extract(audio, 44100, midiHz(48));

        var cfg = new FmPatchSearch.Config(16, 12, Long.MAX_VALUE, 42L, 3);
        var results = FmPatchSearch.search(
                List.of(new FmPatchSearch.Target(target, 48, 0.5)),
                GmVoiceSuggestions.seedBank(), cfg, () -> 0L, g -> {});

        assertFalse(results.isEmpty());
        // fmBass itself is in the seed bank, so the best score must be near zero
        assertTrue(results.get(0).score() < 0.01,
                "best score " + results.get(0).score());
    }

    @Test
    void searchIsDeterministicForSameSeed() {
        FmVoice truth = GmVoiceSuggestions.bell();
        var renderer = new CandidateRenderer();
        SpectralTarget target = SpectralTarget.extract(
                renderer.render(truth.getData(), 60, 0.4, 0.2), 44100, midiHz(60));
        var cfg = new FmPatchSearch.Config(8, 4, Long.MAX_VALUE, 7L, 2);
        var targets = List.of(new FmPatchSearch.Target(target, 60, 0.4));

        var a = FmPatchSearch.search(targets, GmVoiceSuggestions.seedBank(), cfg, () -> 0L, g -> {});
        var b = FmPatchSearch.search(targets, GmVoiceSuggestions.seedBank(), cfg, () -> 0L, g -> {});
        assertArrayEquals(a.get(0).voice().getData(), b.get(0).voice().getData());
    }

    @Test
    void budgetExpiryReturnsBestSoFar() {
        var renderer = new CandidateRenderer();
        SpectralTarget target = SpectralTarget.extract(
                renderer.render(GmVoiceSuggestions.pad().getData(), 60, 0.4, 0.2),
                44100, midiHz(60));
        // elapsed supplier that is instantly over budget → only generation 0 runs
        var cfg = new FmPatchSearch.Config(8, 100, 1L, 1L, 2);
        var results = FmPatchSearch.search(
                List.of(new FmPatchSearch.Target(target, 60, 0.4)),
                GmVoiceSuggestions.seedBank(), cfg, () -> 99999L, g -> {});
        assertFalse(results.isEmpty());
    }

    private static double midiHz(int pitch) {
        return 440.0 * Math.pow(2, (pitch - 69) / 12.0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestFmPatchSearch`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.model.FmVoice;

import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Genetic search over 4-op FM patch space, scored against spectral targets by
 * rendering through the real YM2612. Searched genes: algorithm, feedback,
 * per-op MUL/TL/AR/D1R/SL(D1L)/RR/DT. D2R/RS/AM stay at the seed's values.
 */
public final class FmPatchSearch {

    public record Config(int population, int maxGenerations, long budgetMillis,
                         long seed, int topN) {
        public static Config defaults() { return new Config(32, 40, 10_000, 1234L, 5); }
    }

    public record Target(SpectralTarget spectral, int midiPitch, double keyOnSec) {}

    public record ScoredVoice(FmVoice voice, double score) {}

    private static final double MUTATION_RATE = 0.1;
    private static final int TOURNAMENT = 3;

    private FmPatchSearch() {}

    public static List<ScoredVoice> search(List<Target> targets, List<FmVoice> seeds,
                                           Config cfg, LongSupplier elapsedMillis,
                                           IntConsumer progress) {
        Random rng = new Random(cfg.seed());
        CandidateRenderer renderer = new CandidateRenderer();

        // population: seeds + mutated copies
        List<FmVoice> pop = new ArrayList<>();
        for (FmVoice s : seeds) pop.add(new FmVoice(s.getName(), s.getData()));
        while (pop.size() < cfg.population()) {
            FmVoice base = seeds.get(rng.nextInt(seeds.size()));
            pop.add(mutate(new FmVoice("cand", base.getData()), rng, 3));
        }

        List<ScoredVoice> scored = score(pop, targets, renderer);
        for (int gen = 0; gen < cfg.maxGenerations(); gen++) {
            if (elapsedMillis.getAsLong() > cfg.budgetMillis()) break;
            progress.accept(gen);

            List<FmVoice> next = new ArrayList<>();
            next.add(scored.get(0).voice()); // elitism
            while (next.size() < cfg.population()) {
                FmVoice a = tournament(scored, rng);
                FmVoice b = tournament(scored, rng);
                next.add(mutate(crossover(a, b, rng), rng, 1));
            }
            scored = score(next, targets, renderer);
        }

        // hill-climb the leaders
        List<ScoredVoice> polished = new ArrayList<>();
        for (ScoredVoice sv : scored.subList(0, Math.min(cfg.topN(), scored.size()))) {
            if (elapsedMillis.getAsLong() > cfg.budgetMillis()) { polished.add(sv); continue; }
            polished.add(hillClimb(sv, targets, renderer));
        }
        polished.sort(Comparator.comparingDouble(ScoredVoice::score));
        return dedupByData(polished);
    }

    private static List<ScoredVoice> score(List<FmVoice> pop, List<Target> targets,
                                           CandidateRenderer renderer) {
        List<ScoredVoice> out = new ArrayList<>(pop.size());
        for (FmVoice v : pop) out.add(new ScoredVoice(v, fitness(v, targets, renderer)));
        out.sort(Comparator.comparingDouble(ScoredVoice::score));
        return out;
    }

    /** Mean spectral distance across all target windows. */
    static double fitness(FmVoice v, List<Target> targets, CandidateRenderer renderer) {
        double sum = 0;
        for (Target t : targets) {
            float[] audio = renderer.render(v.getData(), t.midiPitch(), t.keyOnSec(), 0.25);
            SpectralTarget cand = SpectralTarget.extract(audio, 44100,
                    440.0 * Math.pow(2, (t.midiPitch() - 69) / 12.0));
            sum += SpectralTarget.distance(t.spectral(), cand);
        }
        return sum / targets.size();
    }

    private static FmVoice tournament(List<ScoredVoice> scored, Random rng) {
        ScoredVoice best = null;
        for (int i = 0; i < TOURNAMENT; i++) {
            ScoredVoice c = scored.get(rng.nextInt(scored.size()));
            if (best == null || c.score() < best.score()) best = c;
        }
        return best.voice();
    }

    /** Uniform per-operator crossover; algorithm/feedback from a random parent. */
    static FmVoice crossover(FmVoice a, FmVoice b, Random rng) {
        FmVoice child = new FmVoice("cand", (rng.nextBoolean() ? a : b).getData());
        for (int op = 0; op < 4; op++) {
            FmVoice src = rng.nextBoolean() ? a : b;
            child.setMul(op, src.getMul(op));   child.setDt(op, src.getDt(op));
            child.setTl(op, src.getTl(op));     child.setAr(op, src.getAr(op));
            child.setD1r(op, src.getD1r(op));   child.setD1l(op, src.getD1l(op));
            child.setRr(op, src.getRr(op));
        }
        return child;
    }

    /** Mutates `count` random genes by a random step within each parameter's range. */
    static FmVoice mutate(FmVoice v, Random rng, int count) {
        for (int i = 0; i < Math.max(1, poisson(rng, count * MUTATION_RATE * 10)); i++) {
            int gene = rng.nextInt(2 + 4 * 7); // algo, fb, 4 ops × 7 params
            if (gene == 0) v.setAlgorithm(rng.nextInt(8));
            else if (gene == 1) v.setFeedback(rng.nextInt(8));
            else {
                int op = (gene - 2) / 7;
                switch ((gene - 2) % 7) {
                    case 0 -> v.setMul(op, rng.nextInt(16));
                    case 1 -> v.setDt(op, rng.nextInt(8));
                    case 2 -> v.setTl(op, rng.nextInt(128));
                    case 3 -> v.setAr(op, rng.nextInt(32));
                    case 4 -> v.setD1r(op, rng.nextInt(32));
                    case 5 -> v.setD1l(op, rng.nextInt(16));
                    case 6 -> v.setRr(op, rng.nextInt(16));
                }
            }
        }
        return v;
    }

    private static int poisson(Random rng, double mean) {
        double l = Math.exp(-mean), p = 1;
        int k = 0;
        do { k++; p *= rng.nextDouble(); } while (p > l);
        return k - 1;
    }

    /** ±1 step on each gene, keep improvements, two sweeps. */
    static ScoredVoice hillClimb(ScoredVoice start, List<Target> targets,
                                 CandidateRenderer renderer) {
        FmVoice best = new FmVoice(start.voice().getName(), start.voice().getData());
        double bestScore = start.score();
        for (int sweep = 0; sweep < 2; sweep++) {
            for (int op = 0; op < 4; op++) {
                for (int dir : new int[]{-1, 1}) {
                    // TL is the most sensitive gene — climb it per operator
                    FmVoice trial = new FmVoice("cand", best.getData());
                    int tl = Math.max(0, Math.min(127, trial.getTl(op) + dir * 2));
                    trial.setTl(op, tl);
                    double s = fitness(trial, targets, renderer);
                    if (s < bestScore) { best = trial; bestScore = s; }
                }
            }
        }
        return new ScoredVoice(best, bestScore);
    }

    private static List<ScoredVoice> dedupByData(List<ScoredVoice> in) {
        Map<String, ScoredVoice> seen = new LinkedHashMap<>();
        for (ScoredVoice sv : in) {
            seen.putIfAbsent(java.util.HexFormat.of().formatHex(sv.voice().getData()), sv);
        }
        return List.copyOf(seen.values());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestFmPatchSearch`
Expected: PASS (3 tests). The recovery test can take ~30-60 s (16 pop × 12 gens × renders); that is acceptable for this suite, but if it exceeds ~2 min, reduce `keyOnSec` in the test to 0.3.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/match/FmPatchSearch.java app/src/test/java/com/opensmpsdeck/audio/match/TestFmPatchSearch.java
git commit -m "feat: seeded genetic FM patch search with hill-climb refinement"
```

---

### Task 7: VoiceMatchService (async facade)

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/audio/match/VoiceMatchService.java`
- Test: `app/src/test/java/com/opensmpsdeck/audio/match/TestVoiceMatchService.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.GmVoiceSuggestions;
import com.opensmpsdeck.io.midi.MidiStem;
import com.opensmpsdeck.io.midi.NoteEvent;
import com.opensmpsdeck.io.midi.TickTimeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestVoiceMatchService {

    @Test
    void matchesAgainstSyntheticStem() throws Exception {
        // synthesize "stem" audio with a known voice playing an isolated note
        var renderer = new CandidateRenderer();
        float[] stemAudio = renderer.render(
                GmVoiceSuggestions.fmBass().getData(), 48, 0.6, 0.2);
        var notes = List.of(new NoteEvent(0, 576, 48, 100)); // 0.6 s at 120 BPM/480ppq
        var map = new TickTimeMapper(480, List.of(new MidiStem.TempoEvent(0, 500000)));

        var service = new VoiceMatchService();
        AtomicInteger progress = new AtomicInteger();
        var future = service.match(stemAudio, notes, map,
                new FmPatchSearch.Config(8, 4, 60_000, 5L, 3), progress::set);
        var result = future.get(3, TimeUnit.MINUTES);

        assertFalse(result.candidates().isEmpty());
        assertTrue(progress.get() >= 0);
        service.shutdown();
    }

    @Test
    void reportsInsufficientIsolation() throws Exception {
        // two fully overlapping notes → no isolated window
        var notes = List.of(
                new NoteEvent(0, 480, 60, 100),
                new NoteEvent(0, 480, 64, 100));
        var map = new TickTimeMapper(480, List.of(new MidiStem.TempoEvent(0, 500000)));
        var service = new VoiceMatchService();
        var result = service.match(new float[44100], notes, map,
                new FmPatchSearch.Config(8, 2, 60_000, 5L, 3), g -> {})
                .get(1, TimeUnit.MINUTES);
        assertTrue(result.candidates().isEmpty());
        assertNotNull(result.failureReason());
        service.shutdown();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestVoiceMatchService`
Expected: COMPILE ERROR

- [ ] **Step 3: Write the implementation**

```java
package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.NoteEvent;
import com.opensmpsdeck.io.midi.TickTimeMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/** Async facade over the matching pipeline. No JavaFX dependencies. */
public final class VoiceMatchService {

    public record MatchResult(List<FmPatchSearch.ScoredVoice> candidates,
                              String failureReason) {}

    private static final int TOP_WINDOWS = 3;
    private static final int SAMPLE_RATE = 44100;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "voice-match");
                t.setDaemon(true);
                return t;
            });

    public CompletableFuture<MatchResult> match(float[] stemAudio, List<NoteEvent> notes,
                                                TickTimeMapper map, FmPatchSearch.Config cfg,
                                                IntConsumer progress) {
        return CompletableFuture.supplyAsync(() -> {
            var windows = MonophonicWindowFinder.find(notes, map, TOP_WINDOWS);
            if (windows.isEmpty()) {
                return new MatchResult(List.of(),
                        "No isolated note of at least 250 ms found in the MIDI — "
                        + "pick a voice from a bank instead.");
            }
            List<FmPatchSearch.Target> targets = new ArrayList<>();
            for (var w : windows) {
                int start = (int) (w.startSec() * SAMPLE_RATE);
                int len = (int) (w.lengthSec() * SAMPLE_RATE);
                if (start + len > stemAudio.length) continue;
                float[] slice = new float[len];
                System.arraycopy(stemAudio, start, slice, 0, len);
                double hz = 440.0 * Math.pow(2, (w.midiPitch() - 69) / 12.0);
                targets.add(new FmPatchSearch.Target(
                        SpectralTarget.extract(slice, SAMPLE_RATE, hz),
                        w.midiPitch(), Math.min(w.lengthSec(), 1.0)));
            }
            if (targets.isEmpty()) {
                return new MatchResult(List.of(), "Isolated windows fall outside the WAV.");
            }
            long startMs = System.currentTimeMillis();
            var candidates = FmPatchSearch.search(targets,
                    com.opensmpsdeck.io.midi.GmVoiceSuggestions.seedBank(), cfg,
                    () -> System.currentTimeMillis() - startMs, progress);
            return new MatchResult(candidates, null);
        }, executor);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestVoiceMatchService`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/match/VoiceMatchService.java app/src/test/java/com/opensmpsdeck/audio/match/TestVoiceMatchService.java
git commit -m "feat: async voice match service with isolation failure reporting"
```

---

### Task 8: DrumSliceExtractor

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/audio/match/DrumSliceExtractor.java`
- Test: `app/src/test/java/com/opensmpsdeck/audio/match/TestDrumSliceExtractor.java`

- [ ] **Step 1: VERIFY the DAC rate semantics**

Open `synth-core/src/main/java/com/opensmps/smps/DacData.java` (and the `playDac` path in `Ym2612Chip`/`SmpsSequencer`) to find how the rate byte maps to playback sample rate. CLAUDE.md gives mode baseCycles (S1=301, S2=288, S3K=297); the conventional SMPS formula is `playbackHz ≈ Z80_CLOCK(3_546_893) / (baseCycles + rate * 26)` — confirm the exact constants in the code and put the real formula into `dacPlaybackHz` below. The default rate byte `0x0C` used by `InstrumentPanel` should come out in the 10–20 kHz range; if your formula says otherwise, the formula is wrong, not the panel.

- [ ] **Step 2: Write the failing test**

```java
package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.model.DacSample;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestDrumSliceExtractor {

    /** Synthetic kick: 60 Hz sine burst decaying over 150 ms at the given offset. */
    private static float[] clickTrain(double... onsetsSec) {
        float[] audio = new float[44100 * 2];
        for (double onset : onsetsSec) {
            int start = (int) (onset * 44100);
            for (int i = 0; i < 6615 && start + i < audio.length; i++) { // 150 ms
                audio[start + i] += (float) (Math.sin(2 * Math.PI * 60 * i / 44100.0)
                        * 0.8 * Math.exp(-i / 2000.0));
            }
        }
        return audio;
    }

    @Test
    void extractsSliceWithCorrectBounds() {
        DacSample s = DrumSliceExtractor.extract(clickTrain(0.5), 44100, 0.5, 1.5,
                "Kick", 0x0C);
        assertEquals("Kick", s.getName());
        assertEquals(0x0C, s.getRate());
        assertTrue(s.getData().length > 0);
        // slice should be ≲ 200 ms of DAC-rate audio, not the whole 2 s
        assertTrue(s.getData().length < DrumSliceExtractor.dacPlaybackHz(0x0C) / 2);
    }

    @Test
    void sliceEndsAtNextOnset() {
        DacSample s = DrumSliceExtractor.extract(clickTrain(0.5, 0.6), 44100, 0.5, 0.6,
                "Kick", 0x0C);
        // hard-capped at 100 ms by maxEndSec
        assertTrue(s.getData().length <= DrumSliceExtractor.dacPlaybackHz(0x0C) / 9);
    }

    @Test
    void outputIsNormalizedUnsigned8Bit() {
        DacSample s = DrumSliceExtractor.extract(clickTrain(0.5), 44100, 0.5, 1.5,
                "Kick", 0x0C);
        int min = 255, max = 0;
        for (byte b : s.getData()) {
            min = Math.min(min, b & 0xFF);
            max = Math.max(max, b & 0xFF);
        }
        assertTrue(max > 200, "should be normalized near full scale, max=" + max);
        assertTrue(min < 55);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestDrumSliceExtractor`
Expected: COMPILE ERROR

- [ ] **Step 4: Write the implementation**

```java
package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.model.DacSample;

/** Slices a drum one-shot out of stem audio into an unsigned 8-bit DacSample. */
public final class DrumSliceExtractor {

    private static final double SILENCE_DB = -48.0;
    private static final double SILENCE_HOLD_SEC = 0.03;
    private static final double NORMALIZE_PEAK = 0.89; // ≈ -1 dBFS

    private DrumSliceExtractor() {}

    /**
     * Playback rate in Hz for a DAC rate byte.
     * VERIFY against DacData / the sequencer's DAC path and replace the
     * constants with the codebase's real ones (see Task 8 Step 1).
     */
    public static int dacPlaybackHz(int rateByte) {
        final int Z80_CLOCK = 3_546_893;
        final int BASE_CYCLES = 288;       // S2 default; confirm in DacData
        final int CYCLES_PER_RATE_STEP = 26;
        return Z80_CLOCK / (BASE_CYCLES + rateByte * CYCLES_PER_RATE_STEP);
    }

    public static DacSample extract(float[] audio, int sampleRate, double onsetSec,
                                    double maxEndSec, String name, int rateByte) {
        int start = (int) (onsetSec * sampleRate);
        // backtrack to nearest zero crossing (≤ 5 ms) to avoid a click
        int limit = Math.max(0, start - sampleRate / 200);
        while (start > limit && !(audio[start - 1] <= 0 && audio[start] > 0)) start--;

        // end: silence floor held for 30 ms, or the hard cap
        int hardEnd = Math.min(audio.length, (int) (maxEndSec * sampleRate));
        int hop = sampleRate / 100; // 10 ms
        int silentHops = 0;
        int end = hardEnd;
        double silenceAmp = Math.pow(10, SILENCE_DB / 20);
        for (int p = start; p + hop <= hardEnd; p += hop) {
            double sum = 0;
            for (int i = 0; i < hop; i++) sum += audio[p + i] * audio[p + i];
            if (Math.sqrt(sum / hop) < silenceAmp) {
                if (++silentHops * hop >= SILENCE_HOLD_SEC * sampleRate) {
                    end = p;
                    break;
                }
            } else {
                silentHops = 0;
            }
        }
        if (end <= start) end = Math.min(start + hop, audio.length);

        // resample to the DAC playback rate, normalize, convert to unsigned 8-bit
        float[] slice = new float[end - start];
        System.arraycopy(audio, start, slice, 0, slice.length);
        slice = WavStemReader.resample(slice, sampleRate, dacPlaybackHz(rateByte));

        float peak = 1e-6f;
        for (float v : slice) peak = Math.max(peak, Math.abs(v));
        byte[] pcm = new byte[slice.length];
        for (int i = 0; i < slice.length; i++) {
            int v = (int) (slice[i] / peak * NORMALIZE_PEAK * 127) + 128;
            pcm[i] = (byte) Math.max(0, Math.min(255, v));
        }
        return new DacSample(name, pcm, rateByte);
    }
}
```

Make `WavStemReader.resample` public (it is package-private `static` from Task 2 — widen it).

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestDrumSliceExtractor`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/audio/match/DrumSliceExtractor.java app/src/main/java/com/opensmpsdeck/audio/match/WavStemReader.java app/src/test/java/com/opensmpsdeck/audio/match/TestDrumSliceExtractor.java
git commit -m "feat: drum one-shot extraction from stem WAVs into DacSamples"
```

---

### Task 9: UI integration

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/ui/VoiceMatchDialog.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MidiImportDialog.java` ("Match from WAV…" per row, "Extract samples from WAV" checkbox)
- Modify: `app/src/main/java/com/opensmpsdeck/ui/FmVoiceEditor.java` (standalone "Match from WAV…" button)
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindowFileActions.java` (pass stem `.wav` paths through)

- [ ] **Step 1: VoiceMatchDialog**

A modal `Dialog<FmVoice>` that runs the service and lists candidates with audition:

```java
package com.opensmpsdeck.ui;

import com.opensmpsdeck.audio.InstrumentPreviewPlayer;
import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.audio.match.*;
import com.opensmpsdeck.io.midi.NoteEvent;
import com.opensmpsdeck.io.midi.TickTimeMapper;
import com.opensmpsdeck.model.FmVoice;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.List;

/** Runs WAV voice matching and offers the top candidates with audition. */
public class VoiceMatchDialog extends Dialog<FmVoice> {

    private final ListView<FmPatchSearch.ScoredVoice> list = new ListView<>();
    private final ProgressBar progress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final Label status = new Label("Analyzing…");
    private final VoiceMatchService service = new VoiceMatchService();
    private PlaybackEngine previewEngine;

    public VoiceMatchDialog(File wavFile, List<NoteEvent> notes, TickTimeMapper map) {
        setTitle("Match Voice from WAV");
        list.setPrefHeight(180);
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(FmPatchSearch.ScoredVoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : String.format("score %.4f — %s", item.score(), item.voice().getName()));
            }
        });
        Button audition = new Button("Audition");
        audition.setOnAction(e -> {
            var sel = list.getSelectionModel().getSelectedItem();
            if (sel != null && previewEngine != null) {
                InstrumentPreviewPlayer.previewFmVoice(previewEngine, sel.voice(),
                        InstrumentPreviewPlayer.DEFAULT_NOTE);
            }
        });

        VBox box = new VBox(10, status, progress, list, new HBox(10, audition));
        box.setPadding(new Insets(10));
        getDialogPane().setContent(box);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().lookupButton(ButtonType.OK).setDisable(true);
        setResultConverter(bt -> bt == ButtonType.OK
                ? list.getSelectionModel().getSelectedItem().voice() : null);
        setOnHidden(e -> service.shutdown());

        startMatch(wavFile, notes, map);
    }

    public void setPreviewEngine(PlaybackEngine engine) { this.previewEngine = engine; }

    private void startMatch(File wavFile, List<NoteEvent> notes, TickTimeMapper map) {
        new Thread(() -> {
            try {
                float[] audio = WavStemReader.readMono44k(wavFile);
                service.match(audio, notes, map, FmPatchSearch.Config.defaults(),
                                gen -> Platform.runLater(() ->
                                        status.setText("Searching… generation " + gen)))
                        .thenAccept(result -> Platform.runLater(() -> {
                            progress.setVisible(false);
                            if (result.candidates().isEmpty()) {
                                status.setText(result.failureReason());
                            } else {
                                status.setText("Top candidates — audition and accept:");
                                list.setItems(FXCollections.observableArrayList(
                                        result.candidates()));
                                list.getSelectionModel().selectFirst();
                                getDialogPane().lookupButton(ButtonType.OK).setDisable(false);
                            }
                        }));
            } catch (Exception ex) {
                Platform.runLater(() -> status.setText("Failed: " + ex.getMessage()));
            }
        }, "voice-match-load").start();
    }
}
```

Name the accepted voice after the stem: in the OK converter, call `voice.setName(stemFileBaseName + " match")` before returning.

- [ ] **Step 2: Hook into MidiImportDialog**

- Add a `Match…` button column to the mapping table. Enabled only when a `.wav` with the same base name as the row's stem `.mid` exists (pass the original `File` list into the dialog from `MainWindowFileActions.onImportMidi()` so paths are known). On click: `new VoiceMatchDialog(wavFile, row.line.notes(), tickTimeMapper).showAndWait()` and assign the result to `row.voice`, refreshing the table.
- Add `CheckBox extractDrumSamples = new CheckBox("Extract samples from WAV")` in the drums section, default selected when a Drums-stem `.wav` is found. When checked, `buildSpec()` runs `DrumSliceExtractor` per used DAC slot: take the drum-class note lists (kick/snare/tom pitches from the mapping), find the loudest isolated hit via `MonophonicWindowFinder.findDrumHits(classHits, allOtherHits, map, 0.06)`, slice with `DrumSliceExtractor.extract(...)`, and put the results into `MidiImportSpec.dacSampleOverrides()` keyed by slot. Run this synchronously on OK with a wait cursor (slices are milliseconds of audio; it is fast).

- [ ] **Step 3: Hook into FmVoiceEditor**

Add a "Match from WAV…" button beside the existing preview controls. Without MIDI guidance it asks for a pitch (reuse the editor's existing note selector value) and a `.wav` via FileChooser, then constructs a single synthetic `NoteEvent` spanning the loudest 0.5 s region of the file (find it by 100 ms RMS scan) and calls the same `VoiceMatchDialog`. Label the result list "low confidence — no MIDI guidance". On accept, copy the matched parameters into the editor's working voice (`setData`-style: iterate `setOpParam`/`setAlgorithm`/`setFeedback` from the returned voice) and refresh sliders.

- [ ] **Step 4: Compile and test**

Run: `mvn compile -pl app && mvn test -pl app`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: Manual smoke test**

Import the Suno stems (`C:\Users\farre\Downloads\Like We (Chiptune Remix) Stems`), click Match on the Bass row, wait ≤10 s, audition the candidates against the WAV by ear, accept one, finish the import with "Extract samples from WAV" checked, and play. The kick/snare should now be the song's own samples.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui
git commit -m "feat: WAV voice matching and drum extraction UI integration"
```

---

### Task 10: Synthetic end-to-end test

**Files:**
- Test: `app/src/test/java/com/opensmpsdeck/audio/match/TestVoiceMatchEndToEnd.java`

- [ ] **Step 1: Write the test**

```java
package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.io.midi.*;
import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TestVoiceMatchEndToEnd {

    /**
     * Full pipeline with ground truth: a known voice "performs" a melody where one
     * note is isolated; the matcher must return a candidate spectrally close to it.
     */
    @Test
    void matchedVoiceSoundsLikeTheTruth() throws Exception {
        FmVoice truth = GmVoiceSuggestions.brass();
        var renderer = new CandidateRenderer();

        // stem: 1.0 s isolated note at MIDI 55 starting at 0.5 s, padded with silence
        float[] note = renderer.render(truth.getData(), 55, 1.0, 0.2);
        float[] stem = new float[44100 * 2];
        System.arraycopy(note, 0, stem, 22050, Math.min(note.length, stem.length - 22050));

        // matching MIDI: 480ppq, 120 BPM → 1.0 s = 960 ticks, start 0.5 s = 480 ticks
        var notes = List.of(new NoteEvent(480, 960, 55, 110));
        var map = new TickTimeMapper(480, List.of(new MidiStem.TempoEvent(0, 500000)));

        var service = new VoiceMatchService();
        var result = service.match(stem, notes, map,
                        new FmPatchSearch.Config(16, 8, 120_000, 99L, 3), g -> {})
                .get(5, TimeUnit.MINUTES);
        service.shutdown();

        assertFalse(result.candidates().isEmpty());
        double best = result.candidates().get(0).score();
        assertTrue(best < 0.02, "expected near-recovery of seeded truth, score=" + best);
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn test -pl app -Dtest=TestVoiceMatchEndToEnd`
Expected: PASS. `brass()` is in the seed bank so the GA starts at the answer — this validates the *pipeline* (slicing, window finding, target extraction, scoring), not GA convergence from scratch.

- [ ] **Step 3: Run everything**

Run: `mvn test`
Expected: all tests green

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/opensmpsdeck/audio/match/TestVoiceMatchEndToEnd.java
git commit -m "test: synthetic ground-truth end-to-end voice matching"
```

---

## Self-Review Checklist (run after all tasks)

- [ ] Spec coverage: reader (T2), window finder (T4), spectral target (T3), renderer (T5), GA search (T6), service (T7), drum extraction (T8), UI (T9), error handling (T7 failure reasons, T2 unsupported encoding), testing incl. ground-truth round-trip (T6/T10), determinism via injected seed (T6)
- [ ] `CandidateRenderer` register sequence verified audible (T5 step 4 fallback notes)
- [ ] `dacPlaybackHz` constants replaced with the codebase's real DAC math (T8 step 1)
- [ ] `WavStemReader.resample` visibility widened for T8
- [ ] Parallel scoring (spec mentions worker threads) intentionally deferred: the single-threaded search fits the 10 s budget at pop 32 with 0.5 s renders only marginally — if the manual smoke test feels slow, parallelize `score()` with a fixed thread pool of `CandidateRenderer`s (one per thread) as a follow-up
- [ ] CLAUDE.md updated: add `audio.match` package row to the architecture table
