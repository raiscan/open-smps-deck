# CLAUDE.md

## Project Overview

OpenSMPS Deck is a standalone SMPS-native music tracker for composing YM2612 FM and SN76489 PSG music. It targets the Sega Mega Drive / Genesis sound hardware and produces SMPS-compatible output that can be injected into Sonic ROMs or played by the OpenGGF sonic-engine.

**Key principle:** The internal model IS SMPS bytecode. The tracker grid is a decoded view over raw SMPS data. What you hear is exactly what exports.

## Build & Run Commands

```bash
mvn compile                    # Build all modules
mvn test                       # Run all tests (407 tests)
mvn test -pl synth-core        # Run synth-core tests only
mvn test -pl app               # Run app tests only
mvn test -Dtest=TestClassName  # Run a single test class
mvn package                    # Package the application
```

## Architecture

Maven multi-module project with three-layer design:

### 1. synth-core (no UI dependencies)

Pure Java chip emulators and SMPS sequencer. Zero game logic. Public API: `SmpsDriver.read(short[])` produces stereo PCM samples.

| Package | Purpose |
|---------|---------|
| `com.opensmps.synth` | Chip emulators: Ym2612Chip (FM), PsgChipGPGX (PSG), VirtualSynthesizer (mixer), BlipDeltaBuffer/BlipResampler |
| `com.opensmps.smps` | SMPS sequencer: SmpsSequencer (bytecode interpreter), SmpsSequencerConfig (per-game config), SmpsCoordFlags (authoritative flag definitions), AbstractSmpsData, DacData |
| `com.opensmps.driver` | SmpsDriver (multi-sequencer orchestrator), AudioOutput, AudioStream interface |

### 2. app/model (song data model)

SMPS-native data structures. The primary arrangement model is **hierarchical**: Song → HierarchicalArrangement → Chain (per channel) → ChainEntry → Phrase.

#### Core model

| Class | Purpose |
|-------|---------|
| `Song` | Top-level: name, smpsMode, arrangementMode, tempo, dividingTiming, voiceBank, psgEnvelopes, dacSamples, hierarchicalArrangement |
| `HierarchicalArrangement` | Per-channel chains + shared PhraseLibrary. MAX_DEPTH = 4. Cycle detection |
| `Chain` | Ordered list of ChainEntry refs for one channel, with optional loopEntryIndex |
| `ChainEntry` | References a Phrase by ID with transposeSemitones and repeatCount |
| `Phrase` | Named bytecode unit: id, name, channelType, raw SMPS `byte[]` data, sub-phrase refs |
| `PhraseLibrary` | Global phrase pool with auto-incrementing IDs |
| `ChannelType` | Enum: FM, DAC, PSG_TONE, PSG_NOISE. Static `fromChannelIndex(int)` |
| `ArrangementMode` | Enum: STRUCTURED_BLOCKS, HIERARCHICAL |
| `SmpsMode` | Enum: S1, S2, S3K (determines sequencer config and note offset) |

#### Instrument model

| Class | Purpose |
|-------|---------|
| `FmVoice` | Named 25-byte SMPS FM voice (algorithm, feedback, 4 operators). `VOICE_SIZE = 25` |
| `PsgEnvelope` | Named volume step array terminated by 0x80 |
| `DacSample` | Named raw unsigned 8-bit PCM sample with playback rate (0-255) |

#### Supporting model

| Class | Purpose |
|-------|---------|
| `Pattern` | 10-channel bytecode container (used by PatternCompiler internals) |
| `ClipboardData` | Copy/paste state |
| `UndoManager` | Undo/redo stack |

### 3. app/codec (SMPS encoding/decoding)

| Class | Purpose |
|-------|---------|
| `HierarchyCompiler` | Chain + PhraseLibrary → SMPS track bytecode. Inlines unique phrases, CALL for shared, LOOP for repeats, KEY_DISP for transpose |
| `HierarchyDecompiler` | SMPS track bytecode → Phrases + ChainEntries. Three-pass: find subroutines, linear scan, resolve loop target |
| `PatternCompiler` | Compiles full Song → SMPS binary with header, voice table, and per-channel tracks. Mode-aware note compensation |
| `SmpsEncoder` | Keyboard input → SMPS bytecode (note, rest, tie, voice change) |
| `SmpsDecoder` | SMPS bytecode → decoded rows for tracker display. `decodeWithOffsets()` returns byte positions |
| `EffectMnemonics` | Coordination flag mnemonic parsing/display (PAN, DET, VOL, MOD, TRN, etc.) |
| `InstrumentRemapper` | Scans/rewrites voice/PSG instrument references in bytecode |
| `PasteResolver` | Cross-song paste resolution (scan, auto-remap, rewrite) |

### 4. app/audio (playback)

| Class | Purpose |
|-------|---------|
| `PlaybackEngine` | Wires PatternCompiler → SmpsDriver → AudioOutput. Mode-specific baseNoteOffset and baseCycles |
| `PlaybackSliceBuilder` | Creates deep-copied song slices for play-from-cursor. Trims chains and rebases loop points |
| `SimpleSmpsData` | Wraps compiled SMPS binary for the sequencer. Parameterized baseNoteOffset |
| `AdsrEnvelopeCalculator` | FM operator envelope curve calculation for visual preview |

### 5. app/io (file I/O)

| Class | Purpose |
|-------|---------|
| `ProjectFile` | Save/load `.osmpsd` JSON project files (backward-compatible, graceful fallback for removed modes) |
| `SmpsExporter` | Export compiled SMPS binary (`.bin`) |
| `SmpsImporter` | Import raw SMPS `.bin`/`.s3k`/`.sm2`/`.smp` files as Song models with hierarchical decompilation |
| `WavExporter` | Offline render to WAV with configurable loop count and fade-out |
| `OsmpsVoiceFile` | Save/load `.ovm` voice banks (FM voices + PSG envelopes, JSON) |
| `Rym2612Importer` | Import `.rym2612` XML voice patches (XXE-hardened) |
| `DacSampleImporter` | Import WAV/PCM files as unsigned 8-bit DAC samples |
| `RomVoiceImporter` | Scan SMPSPlay `.bin` directories for importable voices |
| `HexUtil` | Shared hex encoding/decoding utility |

### 6. app/ui (JavaFX)

| Class | Purpose |
|-------|---------|
| `MainWindow` | Tab-based multi-document layout, menu bar, transport wiring |
| `SongTab` | Per-song editor context: model, file, dirty flag, UI component references |
| `SongTabCoordinator` | Lifecycle and event coordination for SongTab |
| `SongView` | Left panel: song structure overview showing per-channel chains |
| `ChainEditor` | Chain editing UI |
| `ChainStrip` | Horizontal strip: active channel's chain as clickable phrase cells |
| `BreadcrumbBar` | Navigation path (Song → Chain → Phrase) with click-to-navigate |
| `TrackerGrid` | Canvas-based note/instrument/effect grid with solo/mute, DAC note entry, phrase display |
| `PhraseEditor` | Primary editing surface for phrases (tracker-style grid) |
| `EffectStackEditor` | Effect column add/remove management |
| `OrderListPanel` | Pattern arrangement list (legacy support) |
| `FmVoiceEditor` | Visual FM editor with algorithm diagram, operator sliders, ADSR preview |
| `PsgEnvelopeEditor` | Bar graph PSG editor with click/drag, preview |
| `DacSampleEditor` | DAC sample name + rate editor |
| `InstrumentPanel` | Voice bank, PSG envelope, and DAC sample list management |
| `TransportBar` | Play/stop/pause, tempo, dividing timing, SMPS mode selector |
| `VoiceImportDialog` | Filterable voice import browser with multi-select |
| `ImportPreviewDialog` | SMPS import preview with decompilation visualization |
| `InstrumentResolveDialog` | Cross-song paste instrument resolution (copy/remap/skip) |

## Hierarchical Arrangement Model

The editor uses an LSDJ-style hierarchy: **Song → Chain → Phrase**.

```
Song
└── HierarchicalArrangement
    ├── PhraseLibrary (shared pool of Phrases)
    └── 10 Chains (one per channel: FM1-5, DAC, PSG1-3, Noise)
        └── ChainEntry[] (phrase ID + transpose + repeat count)
```

### Compilation (HierarchyCompiler)

Each Chain compiles independently to a single SMPS track:

| Feature | SMPS Bytecode | Chain Model |
|---------|---------------|-------------|
| Inline phrase | raw bytes | Phrase referenced once |
| Shared phrase | F8 CALL / E3 RETURN | Phrase referenced 2+ times |
| Repeat | F7 LOOP + count | ChainEntry.repeatCount > 1 |
| Transpose | E9 KEY_DISP | ChainEntry.transposeSemitones ≠ 0 |
| Channel loop | F6 JUMP | Chain.loopEntryIndex ≥ 0 |
| Channel end | F2 STOP | No loop point set |

### Decompilation (HierarchyDecompiler)

Three-pass reverse of compilation:
1. Find subroutines (CALL targets → RETURN)
2. Linear scan main stream, split into phrases at structural boundaries
3. Resolve JUMP target to chain entry index

## Channel Mapping

| Index | Type | Hardware |
|-------|------|----------|
| 0-4 | FM 1-5 | YM2612 Ch1-Ch5 |
| 5 | DAC/FM 6 | YM2612 Ch6 (DAC mode) |
| 6-8 | PSG 1-3 | SN76489 Tone 1-3 |
| 9 | PSG Noise | SN76489 Noise |

## SMPS Bytecode Quick Reference

**Notes:** 0x80 = rest, 0x81-0xDF = note values (0x81=C0, +12 per octave)
**Duration:** 0x01-0x7F = frame count (follows note byte)
**DAC notes:** 0x81 + sampleIndex on channel 5

**Key coordination flags** (defined in `SmpsCoordFlags`):
- `EF ii` — Set FM voice
- `F5 ii` — Set PSG envelope
- `E7` — Tie (sustain without re-keying)
- `E0 pp` — Pan (C0=L+R, 80=L, 40=R)
- `E9 ss` — Key displacement (transpose)
- `F0 dd rr dd ss` — Enable modulation
- `F2` — Track end / stop
- `F6 aa aa` — Jump (loop point)
- `F7 ii cc` — Loop (index, count)
- `F8 aa aa` — Call subroutine
- `E3` — Return from subroutine
- `FF 00 tt` — Set tempo

## Mode-Aware Behavior

| Setting | S1 | S2 | S3K |
|---------|----|----|-----|
| Base note offset | 0 | 1 | 0 |
| Tempo mode | TIMEOUT | OVERFLOW2 | OVERFLOW |
| DAC baseCycles | 301 | 288 | 297 |
| Relative pointers | false | false | true |
| Tempo on first tick | false | false | true |

PatternCompiler applies +1 note compensation for S1/S3K modes to match the lower baseNoteOffset at playback time.

## Synth Core Origin

The `synth-core` module is extracted from [OpenGGF](https://github.com/jamesj999/OpenGGF) (`com.openggf.audio.*`). The chip emulators are hybrid ports of libvgm and Genesis Plus GX. When making changes to the synth core, reference:
- `docs/SMPS-rips/SMPSPlay/` in the OpenGGF repo for SMPS driver reference
- libvgm and Genesis Plus GX source for chip accuracy

## Code Style

- Java 21 features (records, switch expressions, pattern matching)
- Source files end with newline
- Keep synth-core free of UI and game dependencies
- SMPS bytecode is always the source of truth — UI reads/writes it directly
- Use `SmpsCoordFlags` constants for all coordination flag bytes (never hardcode)
- Use `FmVoice.VOICE_SIZE` instead of magic number 25
- Use `HexUtil` for hex encoding/decoding (no local copies)
- Use `ChannelType.fromChannelIndex()` for channel classification

## Testing

407 tests across 50 test files:

- **Full-stack:** `TestFullStackRoundTrip` — create/compile/play/export pipeline using hierarchical arrangements, S1 vs S2 mode differentiation
- **Codec:** HierarchyCompiler/Decompiler round-trips, encode/decode, pattern compilation, transpose, coordination flag parity, effect mnemonics, paste resolution
- **Model:** Song, Chain, Phrase, HierarchicalArrangement, ChannelType, FmVoice, PsgEnvelope, DacSample, UndoManager, ClipboardData
- **I/O:** ProjectFile (including hierarchical serialization), SmpsImporter/Exporter, WavExporter (fade-out verification), OsmpsVoiceFile, Rym2612Importer, DacSampleImporter
- **Audio:** PlaybackEngine integration, PlaybackSliceBuilder, SimpleSmpsData header parsing and voice extraction, ADSR envelope calculation
- **UI:** SongTab lifecycle, SongTabCoordinator, MainWindowTabLifecycleCoordinator
- **Synth core:** Ym2612Chip, PsgChipGPGX, VirtualSynthesizer, BlipDeltaBuffer, BlipResampler, SmpsSequencer, SmpsSequencerConfig, SmpsDriver, AudioOutput
- `StubSmpsData` in test sources provides a concrete `AbstractSmpsData` for unit tests

## File Extensions

| Extension | Format | Description |
|-----------|--------|-------------|
| `.osmpsd` | JSON | OpenSMPS Deck project file |
| `.bin` | Binary | Raw SMPS binary export |
| `.wav` | RIFF/WAV | 44.1kHz 16-bit stereo PCM audio |
| `.ovm` | JSON | Voice bank (FM voices + PSG envelopes) |
| `.rym2612` | XML | RYM2612 VSTi patch (import only) |
