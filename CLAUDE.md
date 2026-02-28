# CLAUDE.md

## Project Overview

OpenSMPS Deck is a standalone SMPS-native music tracker for composing YM2612 FM and SN76489 PSG music. It targets the Sega Mega Drive / Genesis sound hardware and produces SMPS-compatible output that can be injected into Sonic ROMs or played by the OpenGGF sonic-engine.

**Key principle:** The internal model IS SMPS bytecode. The tracker grid is a decoded view over raw SMPS data. What you hear is exactly what exports.

## Build & Run Commands

```bash
mvn compile                    # Build all modules
mvn test                       # Run all tests (253 tests)
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

SMPS-native data structures.

| Class | Purpose |
|-------|---------|
| `Song` | Top-level: name, smpsMode, tempo, dividingTiming, voiceBank, psgEnvelopes, dacSamples, patterns, orderList, loopPoint |
| `Pattern` | 10 channels x N rows. Each channel stores raw SMPS track bytecode (`byte[]`) |
| `FmVoice` | Named 25-byte SMPS FM voice (algorithm, feedback, 4 operators). `VOICE_SIZE = 25` |
| `PsgEnvelope` | Named volume step array terminated by 0x80 |
| `DacSample` | Named raw unsigned 8-bit PCM sample with playback rate (0-255) |
| `SmpsMode` | Enum: S1, S2, S3K (determines sequencer config and note offset) |

### 3. app/codec (SMPS encoding/decoding)

| Class | Purpose |
|-------|---------|
| `PatternCompiler` | Compiles Song model -> raw SMPS binary. Mode-aware note compensation for S1/S3K |
| `SmpsEncoder` | Keyboard input -> SMPS bytecode (note, rest, tie, voice change) |
| `SmpsDecoder` | SMPS bytecode -> decoded rows for tracker display |
| `InstrumentRemapper` | Scans/rewrites voice/PSG instrument references in bytecode |
| `PasteResolver` | Cross-song paste resolution (scan, auto-remap, rewrite) |

### 4. app/audio (playback)

| Class | Purpose |
|-------|---------|
| `PlaybackEngine` | Wires PatternCompiler -> SmpsDriver -> AudioOutput. Mode-specific baseNoteOffset and baseCycles |
| `SimpleSmpsData` | Wraps compiled SMPS binary for the sequencer. Parameterized baseNoteOffset |

### 5. app/io (file I/O)

| Class | Purpose |
|-------|---------|
| `ProjectFile` | Save/load `.osmpsd` JSON project files (backward-compatible) |
| `SmpsExporter` | Export compiled SMPS binary (`.bin`) |
| `SmpsImporter` | Import raw SMPS `.bin`/`.s3k`/`.sm2`/`.smp` files as Song models |
| `WavExporter` | Offline render to WAV with configurable loop count and fade-out |
| `VoiceBankFile` | Save/load `.ovm` voice banks (FM voices + PSG envelopes, JSON) |
| `Rym2612Importer` | Import `.rym2612` XML voice patches (XXE-hardened) |
| `DacSampleImporter` | Import WAV/PCM files as unsigned 8-bit DAC samples |
| `RomVoiceImporter` | Scan SMPSPlay `.bin` directories for importable voices |
| `HexUtil` | Shared hex encoding/decoding utility |

### 6. app/ui (JavaFX)

| Class | Purpose |
|-------|---------|
| `MainWindow` | Tab-based multi-document layout, menu bar, transport wiring |
| `TrackerGrid` | Canvas-based note/instrument/effect grid with solo/mute, DAC note entry |
| `OrderListPanel` | Pattern arrangement with add/remove/duplicate/loop |
| `FmVoiceEditor` | Visual FM editor with algorithm diagram, operator sliders, preview |
| `PsgEnvelopeEditor` | Bar graph PSG editor with click/drag, preview |
| `DacSampleEditor` | DAC sample name + rate editor |
| `InstrumentPanel` | Voice bank, PSG envelope, and DAC sample list management |
| `TransportBar` | Play/stop/pause, tempo, dividing timing, SMPS mode selector |
| `VoiceImportDialog` | Filterable voice import browser with multi-select |
| `InstrumentResolveDialog` | Cross-song paste instrument resolution (copy/remap/skip) |

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
- `F0 dd rr dd ss` — Enable modulation
- `F2` — Track end / stop
- `F6 aa aa` — Jump (loop point)
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

The `synth-core` module is extracted from [OpenGGF](https://github.com/jamesj999/OpenGGF) (`com.openggf.audio.*`). The chip emulators are ported from libvgm (YM2612) and Gens Plus GX (PSG). When making changes to the synth core, reference:
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

## Testing

253 tests across 33 test files:

- **Full-stack:** `TestFullStackRoundTrip` — create/compile/play/export pipeline, S1 vs S2 mode differentiation
- **Codec:** Round-trip encode/decode, pattern compilation, transpose, coordination flag parity, paste resolution
- **Model:** Song, FmVoice, PsgEnvelope, DacSample, UndoManager, ClipboardData
- **I/O:** ProjectFile, SmpsImporter/Exporter, WavExporter (fade-out verification), VoiceBankFile, Rym2612Importer, DacSampleImporter
- **Audio:** PlaybackEngine integration, SimpleSmpsData header parsing and voice extraction
- **Synth core:** Ym2612Chip, PsgChipGPGX, VirtualSynthesizer, BlipDeltaBuffer, BlipResampler, SmpsSequencer
- `StubSmpsData` in test sources provides a concrete `AbstractSmpsData` for unit tests

## File Extensions

| Extension | Format | Description |
|-----------|--------|-------------|
| `.osmpsd` | JSON | OpenSMPS Deck project file |
| `.bin` | Binary | Raw SMPS binary export |
| `.wav` | RIFF/WAV | 44.1kHz 16-bit stereo PCM audio |
| `.ovm` | JSON | Voice bank (FM voices + PSG envelopes) |
| `.rym2612` | XML | RYM2612 VSTi patch (import only) |
