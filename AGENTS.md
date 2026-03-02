# AGENTS.md

Custom agents for OpenSMPSDeck development. These are available via Claude Code's Agent tool.

## Available Custom Agents

### smps-bytecode-helper

**Location:** `.claude/agents/smps-bytecode-helper.md`

Use when working with SMPS bytecode encoding/decoding — building test SMPS binaries, debugging track data, verifying coordination flag sequences. Knows the full SMPS command set, note encoding (0x81-0xDF), duration bytes, and all coordination flags. Uses `SmpsCoordFlags.java` as the authoritative reference for flag byte values.

**When to use:**
- Constructing SMPS binary data for tests
- Debugging decoded tracker grid output
- Verifying coordination flag parameter counts
- Understanding note-to-frequency mapping across S1/S2/S3K modes

### synth-core-tester

**Location:** `.claude/agents/synth-core-tester.md`

Use when making changes to the synth-core module. Runs the full test suite (46 tests) and verifies chip emulator behavior hasn't regressed. Covers YM2612, PSG, VirtualSynthesizer, BlipDeltaBuffer, BlipResampler, SmpsSequencer, SmpsSequencerConfig, SmpsDriver, and AudioOutput tests.

**When to use:**
- After modifying any file in `synth-core/src/main/`
- After changing chip register write behavior
- After modifying SMPS sequencer or coordination flag handling
- To verify audio output characteristics (silence, key-on, mute)
