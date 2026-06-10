# WAV Voice Matching & Drum Sample Extraction — Design

## Problem

The MIDI importer (`2026-06-11-midi-import-design.md`) reconstructs *notes* from stem exports, but instruments are assigned from generic banks or GM-based suggestions — the result does not sound like the source song. The stem WAVs contain exactly what each instrument sounds like, and OpenSMPSDeck has an exact YM2612 emulator in-process (`Ym2612Chip`), which makes instrument reconstruction a search problem: render candidate FM voices through the real chip and score them against audio sliced from the stem.

Two concrete capabilities:

1. **FM voice matching** — derive `FmVoice` candidates whose timbre approximates a melodic stem
2. **Drum sample extraction** — slice kick/snare/tom one-shots out of the Drums stem WAV into `DacSample`s, replacing the importer's placeholder DAC slots

Builds on Phase 1; nothing here blocks the importer shipping first.

## Solution

A background **VoiceMatchService** that (a) uses the stem's MIDI to find moments where the WAV plays one isolated sustained note, (b) extracts a spectral + amplitude-envelope target from that slice, and (c) runs a seeded genetic search over 4-operator patch space, rendering each candidate through `Ym2612Chip` and scoring by spectral distance. The top candidates are presented for audition — the user's ear is the final judge, with bank-pick and GM suggestion as ever-present fallbacks.

Drum extraction is simpler: drum MIDI onsets locate isolated hits per drum class; the corresponding WAV slices are trimmed, normalized, and converted through the existing `DacSampleImporter` path into the DAC slots the GM drum mapper assigned.

UI entry points: a **"Match from WAV…"** action on the MidiImportDialog instrument cell (pre-filling the paired `.wav` by filename) and the same action in `FmVoiceEditor` for standalone use. Drum extraction is a checkbox in the importer's Drums section ("Extract samples from WAV") plus a standalone InstrumentPanel action.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Render engine for candidates | In-process `Ym2612Chip`, headless | Scoring against the actual emulator removes model mismatch entirely |
| Target isolation | MIDI-guided: windows where exactly one note sounds | Stems are internally polyphonic; the MIDI knows where they are not |
| Search algorithm | Genetic (pop 32, ~40 generations) seeded with curated preset voices, hill-climb refinement on the best few | FM parameter space is non-convex; GA + good seeds beats either alone |
| Fitness | Log-magnitude spectral distance on harmonic bins + RMS envelope distance (weighted sum) | Captures timbre and articulation; cheap enough for thousands of renders |
| Result handling | Top 3–5 candidates with audition, never auto-applied | Search may converge to something unmusical; the ear decides |
| Compute budget | Background thread, progress bar, hard cap ~10 s per voice (budget-sized generations) | Keeps the import flow interactive |
| Searched parameters | Algorithm, feedback, per-op MUL/TL/AR/DR/SL/RR/DT | The audible core; D2R/RS/SSG-EG fixed to neutral in v1 to shrink the space |
| Drum slice selection | Loudest isolated hit per drum class | Single best exemplar beats averaging smeared hits |
| Failure mode | Service reports "no isolated note found" / "no candidate under threshold"; dialog falls back to bank pick / GM suggestion | Matching is best-effort by design |

## Components

New package `com.opensmps.audio.match` in `app/` (depends on synth-core's `Ym2612Chip`; no synth-core changes). WAV reading via `javax.sound.sampled` (JDK built-in).

### 1. WavStemReader (`audio.match`)

Reads a stem `.wav` (any PCM bit depth/rate `javax.sound.sampled` decodes), mixes to mono `float[]`, resamples to 44.1 kHz to match the emulator output rate. Rejects non-PCM with a clear error.

### 2. MonophonicWindowFinder (`audio.match`)

Input: the stem's quantized `NoteEvent` list (from the Phase 1 pipeline) and the flattened tempo (tick → seconds mapping).

```
1. Build an "active note count over time" profile from the note list
2. Candidate window = interval where count == 1, length ≥ 250 ms
3. Score = duration × isolation margin (distance to nearest neighbour note)
            × velocity (louder notes have better SNR)
4. Return top K windows (default 3) as (startSec, lengthSec, midiPitch)
```

For drum tracks the same logic runs per drum class (kick pitches, snare pitches, tom pitches) with "isolated" meaning no *other* drum hit within ±60 ms.

### 3. SpectralTarget (`audio.match`)

From a WAV slice + known pitch:

- **Spectrum**: Hann-windowed FFT frames (2048 samples, 50% overlap) over the sustain portion (skip first 50 ms attack); average log-magnitude at the first 16 harmonic bins of the known fundamental
- **Envelope**: RMS over 10 ms hops, normalized; attack time, sustain level, decay slope extracted

A self-contained record: `SpectralTarget(float[] harmonicLevels, float[] rmsEnvelope, int midiPitch)`.

### 4. CandidateRenderer (`audio.match`)

Renders an `FmVoice` headlessly: program the voice into `Ym2612Chip` on one channel, key-on at the target pitch, generate the same duration as the target slice, key-off, capture tail. Output goes through the identical `SpectralTarget` extraction so candidate and target are compared like-for-like. Stateless per call; one chip instance per worker thread.

### 5. FmPatchSearch (`audio.match`)

Genome = the searched parameter subset of the 25-byte voice (algorithm, feedback, 4 × MUL/TL/AR/DR/SL/RR/DT). Encoding/decoding uses `FmVoice`'s byte layout — never raw offsets.

```
1. Seed population: curated preset bank (the GM-suggestion voices) + mutated copies
2. Per generation: render + score all genomes (parallel across worker threads),
   tournament selection, single-point crossover, per-gene mutation (rate 0.1)
3. Multi-window scoring: fitness = mean score across all K target windows
   (penalizes overfitting one note)
4. After GA: hill-climb each of the top 5 (±1 step per parameter, keep improvements)
5. Return top N distinct candidates with their scores
```

Generations are sized to the time budget: the search loop checks elapsed time and stops at the cap, returning the best found so far.

### 6. VoiceMatchService (`audio.match`)

Async facade: `match(wavFile, noteEvents, tempoMap, budget) → CompletableFuture<List<ScoredVoice>>` with a progress callback (generation count / elapsed). Cancellable. All UI calls go through this; nothing in `audio.match` touches JavaFX.

### 7. DrumSliceExtractor (`audio.match`)

Per drum class (kick/snare/tom), using MonophonicWindowFinder's drum mode:

```
1. Take the loudest isolated hit
2. Slice from onset (backtrack to nearest zero-crossing) to the earlier of:
   next hit, or RMS falling below −48 dB for 30 ms
3. Trim trailing silence, peak-normalize to −1 dBFS
4. Hand to the DacSampleImporter conversion path (resample to the chosen
   playback rate, unsigned 8-bit) → DacSample named after the class
```

Resulting samples replace the importer's placeholder DAC slots in-place, so the arrangement built by the GM drum mapper plays them with no re-mapping.

### 8. UI integration

- **MidiImportDialog** instrument cell: "Match from WAV…" — file pre-filled by pairing `<stem>.mid` ↔ `<stem>.wav` filenames; modal progress; result list with per-candidate audition (one-note preview through the existing preview path) and Accept
- **MidiImportDialog** Drums section: "Extract samples from WAV" checkbox (default on when a paired Drums `.wav` exists)
- **FmVoiceEditor**: same "Match from WAV…" action for use outside import; without MIDI guidance it asks the user for an approximate pitch and treats the whole file as candidate material, using an energy-based isolation heuristic instead of note data (clearly labelled as less reliable)

## Error Handling

- No window with exactly one note ≥ 250 ms → service completes with an "insufficient isolation" result; dialog shows the reason and leaves the bank-pick fallback
- All candidates above the score threshold → returned anyway, flagged "low confidence"
- Unsupported WAV encoding → per-file error message
- Cancel mid-search → best-so-far results offered, or discarded on dialog close

## Testing

The emulator makes ground-truth testing possible without any real-world audio:

- **Synthetic round-trip**: render a known `FmVoice` to PCM via `CandidateRenderer`, feed that as the "stem" → search must return a candidate whose spectral distance to the target is below threshold (assert on score, not byte equality — FM patches are degenerate)
- **MonophonicWindowFinder**: constructed note lists — overlap exclusion, minimum-length filter, drum-class isolation
- **SpectralTarget**: pure sine input → energy concentrated in harmonic bin 1; known two-harmonic input → expected ratio
- **DrumSliceExtractor**: synthetic click-train WAV with known onsets → slice boundaries, zero-crossing backtrack, normalization; output verified as valid `DacSample` PCM
- **Budget/cancel**: search with a tiny budget returns promptly with a non-empty result; cancellation completes the future
- **Determinism**: search accepts an injected random seed so tests are reproducible

## Out of Scope (Future Work)

- Searching D2R/RS/SSG-EG and LFO parameters
- PSG envelope matching from WAV
- Matching against arbitrary (non-stem) full mixes
- Neural / learned parameter estimation (the GA + emulator loop is the v1 bet)
