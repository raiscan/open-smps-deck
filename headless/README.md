# Headless project tools and S3&K playback

Validate and compile the shared JSON project, or render JSON/standalone `.bin`
to WAV through the pinned OpenGGF production driver, physical policy, session
and accurate FM core. These commands run without JavaFX, an audio device, a ROM,
or an embedded agent. The old app continues to use its original `synth-core` module.

## Run

Requires JDK 25 and Maven. From the repository root:

```sh
mvn -B -pl headless -am package
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar demo /path/to/demo.wav --frames 120
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar render /path/to/song.bin /path/to/song.wav --frames 120
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar demo-project /path/to/project.json
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar validate-project /path/to/project.json
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar inspect-project examples/native-evening-clock.smps.json
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar compile-project /path/to/project.json /path/to/project.bin
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar compile-package /path/to/project.json /path/to/project.smps.zip
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar render-project /path/to/project.json /path/to/project.wav --frames 120
```

A native cue can restore a previous project's live state. Pass
`--previous-project prior.json --previous-frames N` together on
`render-project`. The warm-up advances the prior song in the same session.
The WAV begins at the cue and includes the resumed music after `E2 FF`.

`demo-project` creates an original FM/PSG project that an external agent or the
studio can edit. `validate-project` checks its strict schema, references, hardware
lanes and compiler limits, then reports the project name, sequence/clip counts
and compiled byte count. `compile-project` exports finite standalone S3K bytes.
`render-project` compiles the same source and renders it through the pinned core.

Arrangement projects read/write JSON versions 1–4 and mode `S3K`, with named local FM voices,
monophonic FM/PSG note sequences and linked clips on FM1–FM5/PSG1–PSG3. Clip length
can repeat a sequence or trim its final repetition. Time is integer sequencer
ticks; `ticksPerBeat` controls the ruler and `tempo` is the native S3K byte, not
BPM. Unknown fields, missing fields, overlapping notes/clips and unresolved IDs
are errors. Version2 adds timed native controls and RESET/CARRY repetition; version3
adds typed envelope assets and stable source references. Native voices are exact
25-byte definitions. Version 4 adds a DAC lane with one-shot hits and inline samples.

`render-project` includes declared envelope resources. `compile-package` exports
a deterministic ZIP of baked track bytes, raw envelopes and DAC samples, source and a hashed
manifest. `compile-project` refuses declared envelope and sample assets, including unused ones,
so a bare binary export never silently loses them. The [package contract](../docs/architecture/native-asset-package.md)
explains how the maps feed OpenGGF; the engine does not load the ZIP directly.

[Version 5](../docs/architecture/native-bank-source.md) keeps an exact bank,
song header address and native asset mappings. `inspect-project` reports the
reachable addresses and instructions after playback preflight. Native banks
export via `compile-package`, which preserves their bytes and bindings even
when playback preflight reports an unsupported operation.

`--frames` counts **60 Hz NTSC presentations**, not PCM samples. Its default is
120 and its range is 1–36000. Without an explicit limit, `render-project`
expands the duration for DAC arrangements to include the last sample's tail
and uses the stored preview length for v5 banks. Each presentation services the production session
once, then emits 800 stereo samples. WAV output is 48 kHz, stereo, signed 16-bit
little-endian PCM. Synthesis uses OpenGGF's exact hardware-frame budget, which
adjusts its internal synthesis rate to preserve chip time per presentation.

Output files must not already exist. Validation happens before creating output;
input and output cannot be the same file. Errors go to stderr with exit code 2.
Rendering streams one presentation at a time and uses bounded memory. JSON input
is limited to 1 MiB; compiled output and CLI binary input are limited to one
32 KiB bank. Source reads use `ProjectFiles` so CLI and studio share validation.
The binary demo is an original finite FM/PSG phrase
with a synthetic, deliberately asymmetric FM patch; it contains no ROM assets.

## Deliberately limited input contract

The first CLI proof accepts a **finite linear subset** of standalone S3K SMPS.
These restrictions are in its validator, not in the extracted OpenGGF core:

- Six-byte S3K music header, little-endian file-relative pointers with base zero.
- One mandatory first DAC header slot, up to five following FM slots, and up
  to three PSG slots. Slots are positional: include stopped entries for gaps.
- A local voice table, if used, occupies the end of the binary and contains
  complete native-order 25-byte voices. FM notes require that table; referenced
  voices must exist. No operator-order conversion is applied during rendering.
- Byte-only inputs have no implicit resources: nonzero PSG header or F4/F5
  envelope IDs must resolve through the resource-aware CompiledProject API.
  The DAC slot supports finite explicit rests and mapped one-shot triggers.
  `CompiledProject` supplies both envelope and DAC dependencies.
- Notes/rests, nonzero duration bytes, `E3`/`F2` stops and `E7` holds are accepted.
- Single-operand events: detune (`E1`), absolute volume (`E4`), note stop (`E8`),
  absolute transpose (`ED`) and additive transpose (`FB`); FM pan (`E0`), volume
  (`E6`) and local voice (`EF`); PSG volume (`EC`) and noise control (`F3`).
- Custom F0 modulation consumes exactly four operands; FA disables modulation.
  F4 selects a modulation envelope and PSG F5 selects a volume envelope, each
  with one resolved ID operand (zero disables). Every declared resource is
  validated, including unused assets. See the bounded profile in the project format.
- Every track must terminate before the next track or voice-table boundary.
  Calls, jumps, loops, other envelope commands, direct register writes,
  DAC coordination flags and all other commands are rejected explicitly. Unknown commands
  never silently fall through into notes or partially rendered audio.

A normal legacy OpenSMPSDeck export may contain loops or require external
resources and therefore be rejected. This boundary does **not** claim arbitrary
SMPS/ROM import support. The project compiler emits this supported subset with
inline repeated content, positional stopped channels and local FM voices.
Native envelope semantics outside the bounded profile, FM6/DAC ownership switching and
arbitrary control flow remain future work.

## Embedding and verification

`S3kDemo.song()` returns fresh standalone demo bytes.
`S3kRenderer.render(byte[], int, PcmSink)` validates and copies its input, creates a
fresh session and invokes the sink synchronously once per presentation with a
reused 1600-short interleaved stereo buffer. Copy it if retaining PCM.
`S3kRenderer.render(CompiledProject, int, PcmSink)` and its observer overload
validate all declared resources and install owned native tables before creating
the sequencer/session snapshot. `WaveFile.render(CompiledProject, ...)` uses the
same path and validates before creating the output.

Activation uses `PreparedSmpsMusicActivation` and the actual S3K physical policy;
it does not attach a sequencer directly and bypass the production load boundary.
Each iteration calls `serviceForward()` then `advanceDacIdleLoop(..., 800)`, as
required to preserve session/chip timing and DAC completion handling.

Tests cover independent FM/PSG sound, deterministic output, presentation chunk
sizes, WAV format/length, bounded invalid input and preservation of existing
files. Project CLI tests check source validation, sparse channel positions,
compiler/render integration, PCM equality with direct pinned-core rendering,
bounded reads and file preservation. Upstream-source parity is checked separately
by the repository's pin verification tooling; a passing demo alone is not a claim
of broad ROM parity.
