# CLAUDE.md

## Project Overview

OpenSMPSDeck is a standalone SMPS-native music tracker for composing YM2612 FM and SN76489 PSG music. It targets the Sega Mega Drive / Genesis sound hardware and produces SMPS-compatible output that can be injected into Sonic ROMs or played by the OpenGGF sonic-engine.

**Key principle:** The internal model IS SMPS bytecode. The tracker grid is a decoded view over raw SMPS data. What you hear is exactly what exports.

## Build & Run Commands

```bash
mvn compile                    # Build all modules
mvn test                       # Run all tests
mvn test -pl synth-core        # Run synth-core tests only
mvn test -pl app               # Run app tests only
mvn test -Dtest=TestClassName  # Run a single test class
```

## Architecture

Three-layer design:

### 1. synth-core (no UI dependencies)

Pure Java chip emulators and SMPS sequencer. Zero game logic. Public API: `SmpsDriver.read(short[])` produces stereo PCM samples.

| Package | Purpose |
|---------|---------|
| `com.opensmps.synth` | Chip emulators: Ym2612Chip (FM), PsgChipGPGX (PSG), VirtualSynthesizer, BlipDelta resampling |
| `com.opensmps.smps` | SMPS sequencer: SmpsSequencer (bytecode interpreter), SmpsSequencerConfig (per-game config), AbstractSmpsData (song data interface), DacData |
| `com.opensmps.driver` | SmpsDriver (multi-sequencer orchestrator), AudioStream interface |

### 2. app/model (song data model)

SMPS-native data structures.

| Class | Purpose |
|-------|---------|
| `Song` | Top-level: name, smpsMode, tempo, dividingTiming, voiceBank, psgEnvelopes, patterns, orderList, loopPoint |
| `Pattern` | 10 channels × N rows. Each channel stores raw SMPS track bytecode (`byte[]`) |
| `FmVoice` | Named 25-byte SMPS FM voice (algorithm, feedback, 4 operators) |
| `PsgEnvelope` | Named volume step array terminated by 0x80 |
| `SmpsMode` | Enum: S1, S2, S3K (determines sequencer config) |

### 3. app/ui (JavaFX)

Traditional tracker grid interface.

| Class | Purpose |
|-------|---------|
| `TrackerGrid` | Vertical scrolling note/instrument/effect grid per channel |
| `OrderListPanel` | Pattern arrangement order list |
| `FmVoiceEditor` | Visual FM voice editor with algorithm diagram and operator sliders |
| `PsgEnvelopeEditor` | Bar graph PSG envelope editor |
| `InstrumentPanel` | Voice bank and envelope list management |
| `TransportBar` | Play/stop/pause, tempo, SMPS mode selector |

### 4. app/audio (playback)

| Class | Purpose |
|-------|---------|
| `PatternCompiler` | Compiles Song model → raw SMPS binary blob |
| `PlaybackEngine` | Wires PatternCompiler → SmpsDriver → AudioOutput |
| `AudioOutput` | javax.sound.sampled SourceDataLine streaming |

### 5. app/io (file I/O)

| Class | Purpose |
|-------|---------|
| `ProjectFile` | Save/load `.osmpsd` JSON project files |
| `SmpsExporter` | Export compiled SMPS binary (`.bin`) |
| `SmpsImporter` | Import raw SMPS `.bin` files as Song models |

## Channel Mapping

| Index | Type | Hardware |
|-------|------|----------|
| 0 | FM 1 | YM2612 Ch1 |
| 1 | FM 2 | YM2612 Ch2 |
| 2 | FM 3 | YM2612 Ch3 |
| 3 | FM 4 | YM2612 Ch4 |
| 4 | FM 5 | YM2612 Ch5 |
| 5 | DAC/FM 6 | YM2612 Ch6 |
| 6 | PSG 1 | SN76489 Tone 1 |
| 7 | PSG 2 | SN76489 Tone 2 |
| 8 | PSG 3 | SN76489 Tone 3 |
| 9 | PSG Noise | SN76489 Noise |

## SMPS Bytecode Quick Reference

**Notes:** 0x80 = rest, 0x81-0xDF = note values (0x81=C0, +12 per octave)
**Duration:** 0x01-0x7F = frame count (follows note byte)
**Key coordination flags:**
- `E0 pp` — Pan (C0=L+R, 80=L, 40=R)
- `E1 ii` — Set FM voice
- `E4 ii` — Set PSG envelope
- `E7` — Tie (sustain without re-keying)
- `F0 dd rr dd ss` — Enable modulation
- `F2` — Track end
- `F3 cc aa aa` — Loop (counter + target)
- `F4 aa aa` — Jump (absolute pointer)

## Synth Core Origin

The `synth-core` module is extracted from the OpenGGF sonic-engine project (`com.openggf.audio.*`). The chip emulators are ported from SMPSPlay's libvgm/GPGX cores. When making changes to the synth core, reference:
- `docs/SMPS-rips/SMPSPlay/` in the sonic-engine repo for SMPS driver reference
- libvgm source for chip accuracy

## Code Style

- Java 21 features (records, switch expressions, pattern matching)
- Source files end with newline
- Keep synth-core free of UI and game dependencies
- SMPS bytecode is always the source of truth — UI reads/writes it directly

## Testing

- `TestSmpsData` in test sources provides a concrete `AbstractSmpsData` for unit tests
- Chip emulator tests verify silence on init and audio output on key-on
- Sequencer tests build SMPS binaries by hand and verify playback
- Model tests verify round-trip data integrity

## File Extensions

- `.osmpsd` — OpenSMPSDeck project file (JSON)
- `.bin` — Raw SMPS binary export
- `.osmpsvoice` — Shareable FM voice preset (25 bytes + name)
