# OpenSMPS Deck

A standalone SMPS-native music tracker for composing YM2612 FM and SN76489 PSG music targeting the Sega Mega Drive / Genesis sound hardware.

OpenSMPS Deck produces SMPS-compatible output that can be:
- Injected directly into Sonic 1, 2, or 3&K ROMs
- Played by [OpenGGF](https://github.com/jamesj999/OpenGGF)
- Used with SMPSPlay and other community tools

**Key principle:** The internal model IS SMPS bytecode. The tracker grid is a decoded view over raw SMPS data. What you hear is exactly what exports.

## Features

### Core Tracker
- 10-channel tracker grid (FM1-5, DAC, PSG1-3, Noise) with keyboard-driven note entry
- Pattern-based composition with order list arrangement
- Real-time playback with play/stop/pause controls
- Per-channel solo/mute toggles
- Undo/redo with atomic multi-channel edits
- Selection, copy/cut/paste, and transpose operations

### Instrument Editors
- **FM Voice Editor** — Algorithm diagram, 4-operator parameter sliders, carrier highlighting, copy/paste, preview
- **PSG Envelope Editor** — Clickable bar graph, add/remove steps, preview
- **DAC Sample Editor** — Import WAV/PCM samples, configure playback rate

### Multi-Game Support
- Sonic 1, Sonic 2, and Sonic 3&K SMPS modes
- Mode-aware playback (correct tempo, base note offset, PSG behavior per game)
- Mode-aware compilation with automatic note compensation

### File I/O
- Project save/load (`.osmpsd` JSON format)
- SMPS binary export (`.bin`)
- WAV audio export with configurable loop count and fade-out
- SMPS binary import (`.bin`, `.s3k`, `.sm2`, `.smp`)
- Voice bank import/export (`.ovm` native format)
- RYM2612 voice import (`.rym2612` XML format)
- ROM voice import from SMPSPlay ripped files

### Multi-Document
- Tabbed interface with multiple songs open simultaneously
- Cross-song copy/paste with automatic instrument resolution dialog

## Screenshots

*Coming soon*

## Requirements

- **Java 21** or later
- **Maven 3.8+** for building
- **JavaFX 21** (pulled automatically by Maven)

## Build & Run

```bash
# Build all modules
mvn compile

# Run all tests
mvn test

# Package the application
mvn package

# Run the application
mvn exec:java -pl app -Dexec.mainClass=com.opensmps.deck.Launcher
```

## Architecture

OpenSMPS Deck is a Maven multi-module project with a clean three-layer design:

```
┌─────────────────────────────────────────────┐
│  app (JavaFX UI + Song Model + Codec + I/O) │
├─────────────────────────────────────────────┤
│  synth-core (Chip Emulators + SMPS Driver)  │
└─────────────────────────────────────────────┘
```

### synth-core

Pure Java chip emulators and SMPS sequencer with zero UI or game dependencies. Reusable as a standalone library.

| Package | Purpose |
|---------|---------|
| `com.opensmps.synth` | YM2612 FM synthesis, SN76489 PSG, VirtualSynthesizer mixer, BlipDelta resampling |
| `com.opensmps.smps` | SMPS bytecode sequencer, per-game configuration, coordination flag handling |
| `com.opensmps.driver` | SmpsDriver (multi-sequencer orchestrator), AudioOutput (javax.sound.sampled) |

### app

Song model, codec, I/O, and JavaFX UI.

| Package | Purpose |
|---------|---------|
| `com.opensmps.deck.model` | Song, Pattern, FmVoice, PsgEnvelope, DacSample, SmpsMode |
| `com.opensmps.deck.codec` | PatternCompiler, SmpsEncoder/Decoder, InstrumentRemapper, PasteResolver |
| `com.opensmps.deck.audio` | PlaybackEngine, SimpleSmpsData |
| `com.opensmps.deck.io` | ProjectFile, SmpsExporter/Importer, WavExporter, VoiceBankFile, Rym2612Importer |
| `com.opensmps.deck.ui` | TrackerGrid, FmVoiceEditor, PsgEnvelopeEditor, MainWindow, TransportBar |

### Channel Mapping

| Index | Name | Hardware |
|-------|------|----------|
| 0-4 | FM 1-5 | YM2612 Channels 1-5 |
| 5 | DAC | YM2612 Channel 6 (DAC mode) |
| 6-8 | PSG 1-3 | SN76489 Tone Channels 1-3 |
| 9 | Noise | SN76489 Noise Channel |

## Keyboard Controls

### Note Entry
| Keys | Action |
|------|--------|
| Z-M (lower row) | Notes in current octave |
| Q-U (upper row) | Notes in current octave + 1 |
| S, D, G, H, J | Sharp notes (C#, D#, F#, G#, A#) |
| Period (.) | Insert rest |
| 0-9, A-F | Hex digit entry (instrument/effect columns) |

### Navigation & Editing
| Keys | Action |
|------|--------|
| Arrow keys | Move cursor |
| Shift+Arrow | Extend selection |
| Tab / Shift+Tab | Next/previous channel |
| Ctrl+Up/Down | Change octave |
| F1-F8 | Set octave directly |
| +/- | Transpose selection +/-1 semitone |
| Shift+/Shift- | Transpose +/-12 semitones |
| Delete | Clear cell |
| Insert | Insert blank row |
| Backspace | Delete row and pull up |
| Ctrl+C/V/X | Copy/paste/cut |
| Ctrl+Z/Y | Undo/redo |
| Ctrl+A | Select all |

### Transport
| Keys | Action |
|------|--------|
| Space | Toggle playback |
| Enter | Play from cursor |
| Escape | Stop playback |

### Channel Headers
| Action | Effect |
|--------|--------|
| Click header | Toggle mute |
| Ctrl+Click header | Toggle solo |

## File Formats

| Extension | Format | Description |
|-----------|--------|-------------|
| `.osmpsd` | JSON | OpenSMPS Deck project file |
| `.bin` | Binary | Raw SMPS binary (compatible with SMPSPlay) |
| `.wav` | RIFF/WAV | 44.1kHz 16-bit stereo PCM audio export |
| `.ovm` | JSON | Voice bank (FM voices + PSG envelopes) |
| `.rym2612` | XML | RYM2612 VSTi patch (import only) |

## Synth Core Origin

The `synth-core` module is extracted from [OpenGGF](https://github.com/jamesj999/OpenGGF). The chip emulators are hybrid ports of [libvgm](https://github.com/ValleyBell/libvgm) and [Genesis Plus GX](https://github.com/ekeeke/Genesis-Plus-GX), aiming for hardware-accurate synthesis.

## Testing

253 tests across 33 test files covering:

- **Codec layer** — Encode/decode round-trips, pattern compilation, transpose, coordination flag parity
- **Model layer** — Song, FmVoice, PsgEnvelope, DacSample, UndoManager, ClipboardData
- **I/O layer** — ProjectFile, SmpsImporter/Exporter, WavExporter, VoiceBankFile, Rym2612Importer, DacSampleImporter
- **Audio layer** — PlaybackEngine integration, SimpleSmpsData parsing
- **Synth core** — YM2612, PSG, VirtualSynthesizer, BlipDeltaBuffer, BlipResampler, SMPS sequencer
- **Full-stack** — Create/compile/play/export pipeline, S1 vs S2 mode differentiation

```bash
mvn test                          # Run all tests
mvn test -pl synth-core           # Synth core only
mvn test -pl app                  # App only
mvn test -Dtest=TestClassName     # Single test class
```

## License

This project is licensed under the GNU General Public License v3.0 — see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- **SMPS** format documentation from the Sonic Retro community
- **[libvgm](https://github.com/ValleyBell/libvgm)** — chip emulator reference
- **[Genesis Plus GX](https://github.com/ekeeke/Genesis-Plus-GX)** — chip emulator reference
- **[SMPSPlay](https://github.com/ValleyBell/SMPSPlay)** by Valley Bell — reference SMPS driver implementation
- **[OpenGGF](https://github.com/jamesj999/OpenGGF)** — parent project providing the synth core foundation
