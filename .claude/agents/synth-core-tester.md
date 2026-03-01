---
name: synth-core-tester
description: Runs synth-core test suite and verifies chip emulator behavior
tools:
  - Read
  - Glob
  - Grep
  - Bash
---

# Synth Core Tester

You verify that the synth-core module builds and all tests pass after changes.

## Test Suite

Run: `mvn test -pl synth-core`

### Expected Tests (46 total)

**TestYm2612Chip** (4 tests):
- `chipInitializesAndProducesSilence` — No signal variation without key-on
- `keyOnProducesNonZeroOutput` — Key-on with frequency produces audio
- `setInstrumentAndKeyOn` — 25-byte voice load + key-on produces audio
- `resetProducesSilence` — Reset returns to silence

**TestPsgChipGPGX** (5 tests):
- `chipInitializesWithoutError` — Construction succeeds
- `silencedChipProducesZeroOutput` — silenceAll() produces near-zero
- `toneFrequencyAndVolumeProducesAudio` — Tone generation works
- `resetReturnsToCalmState` — Reset returns to silence
- `muteChannelSilencesIt` — Per-channel mute works

**TestVirtualSynthesizer** (6 tests):
- Mixer initialization, FM/PSG mixing, reset behavior

**TestBlipDeltaBuffer** (9 tests):
- Delta buffer accumulation, clear, read-back behavior

**TestBlipResampler** (9 tests):
- Resampling ratio, output quality, edge cases

**TestSmpsSequencer** (6 tests):
- `driverProducesNonZeroOutput` — SmpsDriver produces non-zero FM output
- `sequencerStandaloneRead` — Standalone SmpsSequencer.read() works
- `getTrackPositionReturnsByteOffset` — Track position reporting
- `getTrackPositionReturnsNegativeForMissingChannel` — Missing channel returns -1
- `readDoesNotHangOnSelfJumpTrack` — Safety limit prevents infinite loop on self-JUMP
- `readDoesNotHangOnSelfLoopingPsgEnvelope` — Safety limit prevents infinite PSG envelope loop

**TestSmpsSequencerConfig** (4 tests):
- `testBuilderDefaults` — Default config values are correct
- `testBuilderOverrides` — Builder overrides apply correctly
- `testFmChannelOrderDefensiveCopy` — FM channel order array is defensively copied
- `testSpeedUpTemposImmutable` — Speed-up tempos list is immutable

**TestSmpsDriver** (2 tests):
- `getTrackPositionDelegatesToMusicSequencer` — Delegates position query to active sequencer
- `getTrackPositionReturnsNegativeWhenNoSequencer` — Returns -1 when no sequencer loaded

**TestAudioOutput** (1 test):
- `testConstructionDoesNotThrow` — AudioOutput construction succeeds without error

## Your Job

1. Run the full test suite
2. Report pass/fail counts
3. If any test fails, read the test source and the relevant implementation to diagnose
4. Report which tests passed, which failed, and why
