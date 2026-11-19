# Headless project tools and S3&K playback

Validate and compile the shared JSON project, or render JSON/standalone `.bin`
to WAV through the pinned OpenGGF production driver, physical policy, session
and accurate FM core. These commands run without JavaFX, an audio device, a ROM,
or an embedded agent. Studio uses the same pinned playback core.

## Import locked-on S3&K music

Use your own unheadered 4 MiB locked-on cartridge image. The importer verifies
the ROM revision before reading its music banks and resource tables.

```sh
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar list-rom-songs /path/to/locked-on.gen
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar import-rom-song /path/to/locked-on.gen 0x0D launch-base.smps.json
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar inspect-project launch-base.smps.json
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar copy-arrangement launch-base.smps.json launch-base-editable.smps.json
```

Use a decimal ID or the `0xNN` form printed by the catalogue. The output
is a v5 native project containing the entire addressed bank and its assets.
Choose a destination that does not exist. The imported preview defaults to
3,600 presentations (60 seconds). Use `--frames` to override WAV length.
Import the previous song separately to render the Extra Life cue (`0x2A`).

`copy-arrangement` reconstructs the native preview as a separate v4 arrangement
with editable clips, notes, voices and DAC samples. Timed effects include
custom modulation, volume and modulation envelopes, PSG noise, pitch-changing
holds, voice changes, SSG-EG, pan, detune and volume. Asset references use
stable IDs. A timeline tick represents one 60 Hz presentation. The copy
expands loops over the preview interval, and its effect clock can differ
from the original on tempo changes. The v5 source retains exact bank
playback and access to driver operations outside the arrangement format.

## Instrument catalogue

Run `catalog --help` for the local index, search, show, match, audition, and apply
commands. `catalog match` ranks chip-rendered variants against a short WAV
excerpt and returns the distances with stable IDs. Each successful operation writes versioned JSON. The
[instrument catalogue guide](../docs/architecture/instrument-catalogue.md#first-pass-in-use)
covers source formats, filters, and Studio deployment.

## Reference audio and review notes

Use these arguments after `java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar`.

```text
reference add PROJECT WAV [--offset S] [--label L] [--role R]
notes list PROJECT [--open]
notes add PROJECT --at S [--end S] [--lane L] [--clip C] --text T --author A
notes resolve PROJECT ID [--reply T --author A]
```

Times are project seconds. Reference offsets may be negative. For example,
`notes add song.smps.json --at 20.125 --lane FM1 --text "Check the attack" --author human`
adds a point note and prints its generated ID. `notes list` prints a JSON
array, and `--open` filters it to unresolved feedback. To resolve with a
reply, supply both `--reply` and `--author`.

Writes are atomic and revision-checked. An open Studio window refreshes a
clean project automatically. See the
[review protocol](../docs/architecture/review-loop.md) for the edit, render
and reply sequence.

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

## Reconstruct a track from MIDI and WAV

Export MIDI and a WAV from the same time origin. Build a local [instrument
catalogue](../docs/architecture/instrument-catalogue.md) before you run:

```sh
deck_jar=headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar
java -jar "$deck_jar" reconstruct song.mid song.wav instruments.json first-minute \
  --seconds 60 --name "Song"
java -jar "$deck_jar" reconstruct song.mid song.wav instruments.json full-song --name "Song"
```

The command creates a new output directory with four files.

- `arrangement.smps.json` contains editable clips and local assets.
- `arrangement.smps.zip` contains the compiled S3&K bank and its resource tables.
- `render.wav` contains the 48 kHz stereo output from the pinned OpenGGF chips.
- `report.json` records input and output hashes, source part IDs, instrument
  shortlists and probe windows, and each note dropped or shortened. Ten-second
  audio sections report mono RMS and pair volume rises within 100 ms. The
  audio statistics measure level and timing, with no estimate of timbre fidelity.

Use `--stem PART=stem.wav` to match a part against a time-aligned stem. Get
zero-based part IDs from the report. The matcher uses the mix for other parts.
It pools candidate rankings across several MIDI note windows and marks choices
with weak evidence. A part can change FM patches in a 16-second section when
a local candidate improves the match on two separate excerpts held out from
the default and local patch selections. The report records the candidates
and both distances. Use `--whole-part-voices` for a single FM patch per part.
Reconstruction excludes track data inferred to be Azure Lake FM voices on the
verified locked-on ROM. Native playback still uses the original bytes.

The matcher auditions the FM patch or PSG envelope the arrangement
can deploy, and DAC samples at their native rates with centered pan.

For GM kick notes 35 and 36, the matcher favors standalone kick samples after
two distinct excerpts support a low-frequency rise and decay. It uses the
catalogue's kick tag or verified Sonic 3 & Knuckles DAC slot roles.

Add `--audio-refine` to check the WAV for missed notes and effects. The pitch
pass can split a held note or fill a short gap when repeat measurements
support a stable pitch. Bass notes can use a longer analysis window down to
MIDI 26 (about 37 Hz). In this range the pitch must hold long enough for
that window to resolve it. The echo pass needs quieter repeats at a consistent
delay on separate notes. It writes a quieter FM copy into a free channel
gap. Dry notes keep their placement. The report identifies accepted pitch
changes, echo delay evidence, the number of echo notes, and unresolved tails.
A tail alone can come from a synth release or reverb. The command records
that case without adding a repeat. Time-aligned stems give both passes
cleaner evidence than the mix.

The arrangement quantizes MIDI timing to 60 Hz and uses five FM channels.
Three PSG channels carry notes that miss an FM slot, within their supported
pitch range. `--fm-only` disables the PSG overflow channels. A single DAC lane
plays percussion, so simultaneous hits compete for one slot. The converter
removes systematic bass octave doubles in synth lead parts. Use
`--keep-octaves` to keep those events in the allocation. It translates note
velocity to coarse tone volume. MIDI controllers, including sustain and
pitch bend, are outside this pass.

Without `--seconds`, the output covers the WAV duration. Bank and source size
limits still apply. Set `--ticks-per-beat` to fit the Studio ruler without
changing playback timing. To check a render from an edited project, use
`render-project` with the report's `presentations` as `--frames`.

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
  with one resolved ID operand (zero disables). FM accepts FF05 SSG-EG and
  FF06 volume envelope selection. The latter requires a declared PSG-volume
  envelope if its ID is nonzero. All resources are validated, including
  unused ones.
- Every track must terminate before the next track or voice-table boundary.
  Calls, jumps, loops, other envelope commands, direct register writes,
  DAC coordination flags and all other commands are rejected explicitly. Unknown commands
  never silently fall through into notes or partially rendered audio.

A normal legacy OpenSMPSDeck export may contain loops or require external
resources and therefore be rejected. Raw binary rendering and locked-on
cartridge import have separate entry points. The project compiler emits this supported subset with
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

## Reconstruction benchmark

Use `bench build-corpus` with a local locked-on ROM and catalogue, then
`bench run` to score reconstruction across MIDI profiles and audio variants.
`--quick` selects four songs, `--holdout` excludes the song's own (non-library)
assets from the catalogue, and `--gold` accepts private hand-labelled lead regions. Use
`bench compare` to gate changes against a saved scorecard. Keep all inputs and
generated datasets outside the repository. See the
[benchmark guide](../docs/architecture/benchmark.md) for commands and metric
definitions.
