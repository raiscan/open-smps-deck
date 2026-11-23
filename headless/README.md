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

Build a local catalogue from a verified Sonic 1 or Sonic 2 REV01 ROM, a
locked-on Sonic 3 & Knuckles ROM, or supported rips. The CLI detects an
explicit ROM's game by its verified revision. Set `--game` to check the game
or to use the matching path saved by `rom set`. With no source flags,
`catalog scan` uses the configured S3K ROM.

```sh
java -jar "$CLI" catalog scan s1-instruments.json --rom "$s1_rom"
java -jar "$CLI" rom set s2 "$s2_rom"
java -jar "$CLI" catalog scan s2-instruments.json --game s2
java -jar "$CLI" catalog scan mixed-instruments.json --rom "$s1_rom" --rom "$s2_rom" --rom "$s3k_rom"
java -jar "$CLI" catalog search s2-instruments.json --target s2 --kind fm-voice
java -jar "$CLI" catalog show s2-instruments.json "$variant_id" --brief
java -jar "$CLI" catalog audition s2-instruments.json "$variant_id" audition.wav
java -jar "$CLI" catalog match s2-instruments.json excerpt.wav --target s2 --kind fm-voice
```

Repeat `--rom` to index several games together. Use `--rips DIRECTORY` to
index rips on their own or alongside ROM sources. Keep indexes built from
ROMs in a private directory. Rip sources include editable `.smps.json`
projects, self-contained S1/S2 portable `.smps.zip` packages, and supported
S3K banks and project packages. Loose SM1/SM2 rips require a separate adapter.

Search without `--target` lists all drivers. Audition uses the variant's
driver and accepts `--target` to select a matching variant or check
compatibility. S1 and S2 audition reject FM volume envelopes, modulation
envelopes, and SSG-EG. `catalog match` defaults to S3K. It ranks variants
from the selected driver against a short WAV excerpt. Reconstruction uses
the driver saved in the work directory or selected by `--target` on a
fresh run. Automatic catalogue selection is game-specific for FM patches,
PSG envelopes, and DAC variants. Older indexes remain S3K catalogues.

Each successful operation writes versioned JSON. See the
[instrument catalogue guide](../docs/architecture/instrument-catalogue.md#first-pass-in-use)
for formats and Studio deployment. MCP `catalog_search` and
`catalog_audition` accept the same optional `target` field
(`s1`, `s2`, or `s3k`).

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

For an excerpt, use `render-project PROJECT.json OUT.wav --start S --end S`.
Bounds use project seconds rounded to 60 Hz. Playback warms up from zero
and discards audio before the start. Omit the start to begin at zero.
Omit the end to use `--frames` if supplied, or the project's presentation
count for a bounded render. `--solo FM1,PSG1` mutes all other lanes and
`--mute DAC,PSG3` mutes the listed lanes even when soloed. Masked WAVs
from this command and MCP `render_clip` subtract the YM2612's modeled idle
level of 288 PCM counts per channel (−41.1 dBFS). Full, unmasked renders
retain the raw chip output.

Pass `--force` to `render-project` or `render-compare` to replace an existing
regular file with a completed export staged in the same directory.

For aligned A/B listening, use `render-compare WORK_DIR --start S --end S OUT.wav`.
Bounds default to source seconds and are mapped to project time. Use
`--time project` to specify project seconds directly. The export appends the
level-matched reference after the render. A warped run uses its saved
`reference-steady.wav`. Use `--render WAV` for a replacement project-time
render, or `--reference WAV` for another aligned source-time reference.
Use an `.mp3` output with ffmpeg installed. The JSON result gives the
split point, both gains, and the bounds on both clocks.

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
Rendering streams one presentation at a time and uses bounded memory. Project
source uses the [shared 16 MiB UTF-8 limit and JVM setting](../project-core/README.md#version-1-contract).
Compiled output and CLI binary input are limited to one 32 KiB bank.
The binary demo is an original finite FM/PSG phrase
with a synthetic, deliberately asymmetric FM patch; it contains no ROM assets.

## Export project output

```sh
java -jar "$deck_jar" export WORK_DIR song.wav --format wav
java -jar "$deck_jar" export PROJECT.smps.json clip.mp3 --format mp3 --start 12 --end 24 --fade 1
java -jar "$deck_jar" export WORK_DIR stems.zip --format wav --stems --zip
java -jar "$deck_jar" export NATIVE.smps.json song.wav --format wav --loops 3
java -jar "$deck_jar" export WORK_DIR song.vgm --format vgm
java -jar "$deck_jar" export WORK_DIR song.smps.zip --format package
java -jar "$deck_jar" export song.smps.zip song-kit.zip --format disasm
```

WAV and MP3 require ffmpeg on `PATH` (with libmp3lame for MP3). WAV preserves
48 kHz stereo PCM. Span bounds are project seconds rounded to presentations.
Fade length is in seconds and stays inside the exported span. Without
`--zip`, stems go into a new directory. The stems are FM1–FM5, PSG1–PSG3,
PSG noise and DAC, which uses the FM6 hardware output. Export uses the full
reconstruction interval from an attached work directory and the editable
arrangement's length after detachment. Native exports use the detected end or
loop, with the project preview length as a fallback. `--loops` requests
1–99 passes of a detected native loop, subject to the ten-minute bound.

VGM exports the full song at NTSC YM2612 and Sega PSG clocks. It encodes DAC
bytes as YM2612 register writes, uses the project title in GD3, and marks
the loop when the native tracks share a repeat point. VGM uses a 44.1 kHz
event clock and hardware time. The WAV renderer presents 60 frames per
second, so their nominal durations differ slightly.

`--format smps` is equivalent to `compile-project` and targets a compatible
S3K driver with local voices. Use the package format when the project declares
external envelope or DAC sample assets.
`--format package` is equivalent to `compile-package` and includes the
addressed bank, bindings, resources and source required for integration with
the pinned OpenGGF S3K driver. OpenGGF has no direct ZIP loader.

Use `--format disasm` with a project, work directory or embedded-bank
portable `.smps.zip`.
Portable packages target s1disasm, s2disasm or skdisasm according to their
driver. For a classic kit, first use `compile-package --portable --driver s1`
or `--driver s2`. A `.zip` output wraps the files in a kit directory.
Use `--rom PATH` to materialise stock assets for personal use.
`--dpcm-closed` changes the WAVs to DPCM-representable PCM. Repeat
`--replace-dac NN` (hexadecimal) to repurpose audited retail notes. S1 custom
DAC requires explicit kick or snare replacement. S2 can also extend the DAC
playlist. See the
[disassembly kit integration guide](../docs/architecture/disassembly-kit.md).

MCP exposes `project_export` with exactly one of `project` or `workDir`, plus
`output`, `format`, and the optional `start`, `end`, `fade`, `loops`, `stems`
and `zip` fields. Use a new output inside the project or work directory.
For `format: "disasm"`, `project` can also point to an embedded-bank portable package.
Use optional `roms`, `dpcmClosed` and `replaceDac` (integer note array).
Inspect the returned `warnings`.

## ROM library and BPS output

Set one clean retail ROM path per game for Test in game exports.

```sh
java -jar "$deck_jar" rom set s3k "/path/to/locked-on.gen"
java -jar "$deck_jar" rom list
java -jar "$deck_jar" rom clear s3k
```

Use `s1`, `s2` or `s3k`. The library stores absolute paths in
`${XDG_CONFIG_HOME:-~/.config}/opensmpsdeck/roms.json`. It verifies the full-file
SHA-1 on every use. `rom list` reports `missing` for an unset game, `ok` for a
verified file or `changed-on-disk` when a saved path is unreadable or fails
verification. `rom clear` removes the setting and leaves the file alone.
MCP provides `rom_list` and `rom_set` with `game` and an absolute `path`.

| Game ID | Supported raw retail image | Size (bytes) | CRC32 |
|---|---|---:|---|
| `s1` | Sonic 1 World REV01 | 524,288 | `AFE05EEE` |
| `s2` | Sonic 2 World REV01 | 1,048,576 | `7B905383` |
| `s3k` | Sonic 3 & Knuckles (locked-on) | 4,194,304 | `63522553` |

Convert headered or interleaved SMD dumps to raw, deinterleaved `.bin` or
`.gen` before setting a path. S3K export is available. S1 and S2 export are coming
soon. An explicit `--rom PATH` overrides the library for the current export
without changing the saved path.

An engine binds its game ID with `RomTarget<S, O, R>` in
`com.opensmpsdeck.headless.rom`. Its engine function takes
`(VerifiedRom clean, S compiledSong, O options)` and returns
`PatchedRom<R>` with the patched bytes and game-specific report.
`RomLibrary.resolve(game, override)` checks the game and provides the verified
snapshot. `RomExport.write(clean, patched, romPath, bpsPath, force)` takes
either output path or both. Suggested names are `song.s3k.gen` and
`song.s3k.bps`. The writer reapplies the staged BPS to the clean ROM and
compares the result byte for byte before publication. Each destination is
published atomically where the filesystem supports hard links. On FAT32 or
exFAT cards, it falls back to a plain move that still refuses an existing file.

A patched ROM contains copyrighted data from the retail ROM. Keep it for
personal use and do not redistribute it. BPS is the shareable form and
requires the matching clean ROM to apply. Material derived from stock assets
used by the song remains subject to the original rights. Test the resulting ROM
on your own emulator or hardware. Real-hardware testing is performed by users.

## Test an arrangement in game

Editable project JSON defaults to S3K. Pass `--driver s1` or `--driver s2`
to compile for Sonic 1 or Sonic 2, or supply a portable package that declares
one of those drivers. S3K also accepts legacy packages with embedded source
and track. Select a clean World REV01 S1 or S2 ROM or a locked-on S3K ROM in
the library, or pass `--rom`.

```sh
java -jar "$deck_jar" rom-patch song.smps.json --out song.s3k.gen --bps song.s3k.bps --no-demo
java -jar "$deck_jar" rom-patch song.smps.zip --rom locked-on.gen --bps song.s3k.bps --slot 0x24 --title-music
java -jar "$deck_jar" export song.smps.json song.s3k.gen --format rom --no-demo
java -jar "$deck_jar" rom-patch song.smps.json --driver s1 --out song.s1.gen --no-demo
java -jar "$deck_jar" rom-patch song.smps.json --driver s2 --out song.s2.gen --no-demo
java -jar "$deck_jar" rom-patch song.s2.smps.zip --bps song.s2.bps --no-demo
```

- Supply at least one of `--out` (patched ROM) and `--bps` (verified BPS).
  The CLI aliases `export PROJECT OUTPUT --format rom|bps` select the output
  type for `OUTPUT` and accept the same ROM and music options.
- `--slot ID` selects a music request from `0x01` to `0x33` for S3K
  (title default `0x25`), `0x81` to `0x93` for S1 (title default `0x8A`),
  or `0x81` to `0x9F` for S2 (title default `0x99`). Add `--title-music`
  to request a different slot from the title. Game events that use the
  selected slot also play the replacement.
- `--no-demo` disables the idle title demo. Start still enters the game.
  Studio enables this by default.
- On S2 and S3K, `--loop` replaces each channel's final stop with a jump to
  its start when the active channels reach their stops on the same tick and
  loop control is supported. S2 loop generation requires tracks without
  existing `F6` jumps and is unavailable for the Extra Life slot.
- `--force` allows replacing existing output files. Keep outputs separate from
  the source project and clean ROM.

MCP exposes `rom_patch` with absolute `project` and optional absolute
`rom`, `out`, and `bps` paths. Provide at least one of `out` and `bps`.
Other optional fields are `driver` (`s1`, `s2` or `s3k`), `slot` (integer),
`titleMusic`, `noDemo`, `loop`, and `force` (booleans). CLI and MCP return
output paths, `report` (cartridge hashes, patched ranges, DAC and envelope
mappings), `warnings`, and `bpsStats` when a BPS is written.

A patched ROM is for personal use only. Do not redistribute it. A BPS is
the shareable form and requires the matching clean ROM. Material derived
from stock assets remains subject to its original rights. The compiled song
must fit in one 32 KB music bank. S2 uses an expansion bank and stock DAC
samples. See the [Test in game user guide](../docs/user-guide/14-test-in-game.md)
and the layouts for [S1](../docs/architecture/s1-rom-patch.md),
[S2](../docs/architecture/s2-rom-patch.md) and
[S3K](../docs/architecture/s3k-rom-patch.md).

## External stem separation

Start with a local WAV or MP3 to build an S3K arrangement without MIDI.
Decode FLAC to WAV first with `ffmpeg -i mix.flac -c:a pcm_f32le mix.wav`.
Set `CLI` to the absolute path of the packaged headless JAR and use a
local [instrument catalogue](../docs/architecture/instrument-catalogue.md).
Configure the [BS-RoFormer SW separator](../tools/separators/bs-roformer/)
and [MuScriptor Medium adapter](../tools/transcribers/muscriptor/) under
the names shown here. Run in a private workspace.

```sh
java -jar "$CLI" reconstruct separate mix.wav separated --separator bs-roformer --device auto
java -jar "$CLI" reconstruct import separated import-work \
  --catalogue catalogue.json --transcriber muscriptor-medium
java -jar "$CLI" reconstruct import separated import-work \
  --catalogue catalogue.json --transcriber muscriptor-medium --run
```

For a short baseline, cut a short excerpt before separation.
The separator output directory must be new and its parent must exist.
The adapter writes the decoded mix to `mix.wav`, aligned WAVs to
`stems/NAME.wav`, and model provenance to `separation.json`. The CLI
publishes the folder after validating the files and matching sample rates,
channel counts and frame counts.

On an initial reconstruction with unmapped MIDI drums, a configured
`bs-roformer` command automatically separates the mix on its automatic
device and supplies the aligned drums to the DAC stem-hit fitter. Audio-only
runs without a supplied drum stem also use those drums for onset detection.
The work directory saves the generated drums WAV in `snapshots/` and records
the adapter provenance and stem hash in `manifest.json`. Resumes and reruns
reuse the saved inputs. If `bs-roformer` is unavailable in the config, the CLI
warns and uses the mix. Pass `--separator-config FILE` on `run`, `transcribe`
or `import`, or set `OPENSMPSDECK_SEPARATORS` for CLI and MCP.

Check `import-work/import.json` for errors, nearly silent stems and the
assigned roles. `separation.json` enables audio-only import automatically
for this folder without MIDI. Vocals are candidates for lead and drums feed
the onset detector. Use `--stem-role FILE=ROLE` with a folder-relative file
name to override a role. Repeat the checked options with `--run` to build
in `import-work/reconstruction`. Open this directory in Studio with
**Open reconstruction…**. It must be new for an initial build.

The CLI reads `$XDG_CONFIG_HOME/opensmpsdeck/separators.json` (default
`~/.config/opensmpsdeck/separators.json`). Set `OPENSMPSDECK_SEPARATORS`
or pass `--separator-config FILE` to choose a different file. The JSON maps
names to trusted executable argument arrays. The CLI substitutes
`{input}`, `{outputDir}` and `{device}` directly. `--device` accepts
`auto` (the default), `cpu`, `rocm` and `cuda`. Put the tonal adapter in
[the transcriber configuration](#external-transcription). Import selects a
sole configured transcriber automatically. With several configured, use
per-role defaults or explicit transcriber options. With no configured transcriber and no name
requested, the separated-folder check writes a report and stops with
`no MIDI: needs audio-only transcription`.

For MCP, call `separators_list` and `transcribers_list` to find the server's
configured names. Call `separate` with `input`, `outputDir`, `separator`
and optional `device`. Call `reconstruct_import` with the resulting
`folder`, a separate `workDir`, `catalogue` and `transcriber`. Pass the
checked `runRequest` to `reconstruct_run`.

### Recommended extraction models

The [extraction bake-off](../experiments/extraction-bakeoff/RESULTS.md)
supports starting with BS-RoFormer SW, MuScriptor Medium for bass, guitar,
piano and other tonal material, and ADTOF for drums. Configure
[RMVPE](../tools/transcribers/rmvpe/) for syllable-aware vocal leads.
Basic Pitch remains a vocal alternative.
In the four-track [Suno head-to-head](../experiments/extraction-bakeoff/README.md#original-midi-render-head-to-head),
the local Medium + ADTOF route scored 0.775 onset F1 against 0.429 for the
per-stem Suno MIDI after delay correction. The commands above explicitly
select SW and Medium. The integrated
audio-only build uses the configured RMVPE command for vocals when available
and the built-in spectral detector on drums by default. For separate
vocal and drum estimates, use the
[bake-off environments and CLI](../experiments/extraction-bakeoff/README.md#run-basic-pitch-and-adtof-on-separated-stems).
Those estimates are independent of the audio-only import.

## Choose a reconstruction target

Use `bench live-edit WORK_DIR JOURNAL.json --target s1|s2|s3k` to measure
headless live replay and verify full-song PCM against a canonical rebuild.
See the [live-edit benchmark](../docs/architecture/benchmark.md#live-edit-latency-and-parity)
for options.

Set `--target s1|s2|s3k` on `reconstruct import`, `run`, or `transcribe`
to choose the game before analysis. The default is `s3k`. Import includes
the selected target in its runnable request. Subsequent stages and
`reconstruct rerun` inherit the saved target. You can also pass `--target`
to those commands to assert the saved choice.

S1 and S2 reconstruction use the selected driver's NTSC duration clock.
Notes are placed at the nearest playable presentation, accounting for the
driver's periodic duration holds. The score's `ticksPerSecond` is nominal
and `frames` counts 60 Hz audio presentations. Assembly uses the selected native
compiler and reports incompatible assets or commands, such as S3K PSG
control bytes or unsupported envelope and noise commands. A successful
classic assembly writes a portable package and a render for the selected
driver. Studio uses the saved target for playback.

S1 and S2 default to the target game's stock DAC kit. Register the supported
clean cartridge with `rom set s1 PATH` or `rom set s2 PATH` before fitting a
project with DAC drums. The matcher uses each stock note at its native rate.
The local project contains playable samples and the portable package records
stock references resolved from the recipient's ROM.

Use `--drum-kit custom` on `reconstruct run`, `transcribe`, or `import` to opt
into custom PCM on S1 or S2. Pass `--drum-source catalogue|sliced|auto` on a
fresh staged `run` or `transcribe` to select the PCM source. These settings
persist with the reconstruction. S3K defaults to the existing catalogue
fit. The portable drivers support up to 95 virtual DAC bindings and 512 KiB
of decoded PCM. Test in game reports any approximations. S1 has up to three
custom DPCM entries sharing 1,030 encoded bytes, and S2 maps custom PCM to
stock drums.

The target is saved as `inputs.target` in `score.json`, `options.target`
in `manifest.json`, and `target` in the arrangement project. An omitted
field means S3K. Portable and mod export default to the project target.
An explicit `--driver` overrides that default. Classic builds store
`nativeTempo` in the project so same-target exports use the exact native byte.

## Import a Suno download

```sh
DECK_JAR=headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar
java -jar "$DECK_JAR" reconstruct import "$download" "$work" --catalogue instruments.json
java -jar "$DECK_JAR" reconstruct import "$download" "$work" --catalogue instruments.json \
  --assign 'stems/synth.wav=0' --exclude 'Title (FX).wav' --run
```

Import checks the full mix, MIDI, root or `stems/` WAV files, and optional
`stems.json`. It prints a ✓ pass, ⚠ warning and ✗ error table and writes
`import.json` with file hashes, MIDI part IDs, audio mapping scores, alignment
and a reconstruction command. The automatic matcher maximizes the total score across all pairs,
with small bonuses for matching names and MIDI program families. For each
automatic match, the report shows the winning score and any runner-up stem.
A stem that also strongly correlates with a part gets a warning and a hint
for selecting it as that part's single fitting reference.

Unmapped MIDI parts use the mix. Vocals and FX stay unmapped by default.
Use repeatable `--assign FILE=PART` and `--exclude FILE` with
download-relative paths, including `stems/` where applicable. Supply the
desired overrides again when re-checking. A single missing mix or MIDI
produces a warning and needs to be supplied before reconstruction can start.

With `--run`, supply a trusted catalogue via `--catalogue`. Reconstruction
starts when the check has no errors and both the mix and MIDI are present.
It writes into the new `WORK_DIR/reconstruction` directory. Without a
catalogue, set `CATALOGUE` before using the printed command. Keep the
work directory separate from the read-only download. In MCP, call
`reconstruct_import` and pass its ready `runRequest` to `reconstruct_run`.

## Reconstruct a track from MIDI and WAV

Export MIDI and a WAV from the same time origin. Build a local [instrument
catalogue](../docs/architecture/instrument-catalogue.md) before you run:

```sh
deck_jar=headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar
java -jar "$deck_jar" reconstruct run song.mid song.wav instruments.json first-minute \
  --seconds 60 --name "Song"
java -jar "$deck_jar" reconstruct run song.mid song.wav instruments.json full-song --name "Song"
```

The command creates a new work directory and runs seven stages. Use
`reconstruct run --from STAGE WORK_DIR` to regenerate downstream decisions
after a journal or configuration edit. Treat `score.json` and
`allocation.json` as generated evidence. Allocation prefers the prior
lane where it can do so with the same hardware priority and without
shortening another note. The
[reconstruction stage guide](../docs/architecture/reconstruction-stages.md)
documents the files, individual stage commands, and how to add external
note candidates. Use `reconstruct voice WORK_DIR` to fit `voicing.json`.
Record musical changes with [persistent edits](#persistent-musical-edits) and use
[voicing settings](#refine-and-audition-voicing) for patch and level fitting.

- `arrangement.smps.json` contains editable clips and local assets.
- `arrangement.smps.zip` contains the compiled S3&K bank and its resource tables.
- `render.wav` contains the 48 kHz stereo output from the pinned OpenGGF chips.
- `report.json` records input and output hashes, source part IDs, instrument
  shortlists, effect-copy lanes, and allocation drops and shortenings.
  `chordAudit` classifies unallocated tonal notes by song and effective musical
  section. The same allocation audit appears in `describe` and MCP
  `reconstruct_describe`. Audio-only losses include roles and recorded
  competing note IDs. Select a missed note in Studio Detail to see its
  reason. Ten-second
  audio sections report mono RMS and pair volume rises within 100 ms. The
  audio statistics measure level and timing, with no estimate of timbre fidelity.

Use `--stem PART=stem.wav` to match a part against a time-aligned stem. Get
zero-based part IDs from the report. The matcher uses the mix for other parts.
It pools candidate rankings across several MIDI note windows and marks choices
with weak evidence. Fresh staged fits share a sound across the function
voices of each source instrument by default. See
[voicing](#refine-and-audition-voicing) for scoped overrides and audition.
Reconstruction excludes track data inferred to be Azure Lake FM voices on the
verified locked-on ROM. Native playback still uses the original bytes.

The matcher auditions playable FM patches and PSG envelopes. The staged
DAC fit uses mapped drum stems to compare isolated hits per MIDI class.
Custom kit fitting tests catalogue samples at nearby target-driver rates.
It uses catalogue class evidence and favors a shared kit when the choices are
close. The stock kits use fixed native rates. Stock-kit balance adjusts
tonal lane volumes against the reference stems while preserving DAC PCM. Check the
saved distances and rates in `voicing.json`. See the
[voice stage guide](../docs/architecture/reconstruction-stages.md#voice)
for matching details.

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

The arrangement quantizes timing to 60 Hz. FM1 carries melody and FM2 carries
bass. Counter and harmony use the three remaining FM channels and can spill
to PSG within its pitch range. Rapid inner chord re-attacks can be merged.
`--harmony-max-hz 0` preserves those attacks for comparison. `--fm-only`
disables PSG. DAC carries the primary drum hits. Colliding cymbals and cymbals
measured quiet in a dedicated drum stem can use PSG3 noise. Explicitly named
`PSG3` or `PSG noise` percussion parts also use PSG3 when enabled. Automatic
closed and pedal hats switch from 13.98 kHz to 6.99 kHz after one tick.
See the [PSG3 hat recipe](../docs/architecture/reconstruction-stages.md#tune-or-override-psg3-hats)
for voicing fields and audition commands. The converter
removes systematic bass octave doubles in synth lead parts. Use
`--keep-octaves` to keep those events in the allocation. It translates note
velocity to coarse tone volume. MIDI controllers, including sustain and
pitch bend, are outside this pass.

Without `--seconds`, reconstruction processes the full WAV. By default a
confident gradual tempo curve with at least 0.85% sustained deviation is
corrected to the opening tempo. Use `--steady-tempo off` to keep source timing or supply a numeric target
BPM. Warped runs include a pitch-preserving `reference-steady.wav` for A/B in
Studio and MCP. Use `reconstruct time-map WORK_DIR --source S` or `--project S`
to map times between the two timelines. Bank and source size limits still apply. Set `--ticks-per-beat` to fit the Studio ruler without
changing playback timing. To check a render from an edited project, use
`render-project` with the report's `presentations` as `--frames`.

### Refine and audition voicing

Automatic fitting chooses exact FM patches and compatible PSG envelopes from
the target stock catalogue. Intentional operator changes through a scoped
`minimumReleaseRate` or a `chordInstrument` edit export as custom clones
with their source asset ID in `origin.clonedFrom`.

Fresh staged reconstructions fit one shared sound per source instrument in
`voicing.json.sourceInstruments`. Line and chord function voices share this
sound unless assigned explicitly. Lane overrides and scoped edits remain
available. When source stems support it, drum-to-tonal level fitting uses
gated K-weighted loudness. RMS in the reports and audition output describes
signal level.

Use `reconstruct voice WORK_DIR --lane FM1` to refit only FM1. Refits preserve
existing gains for unchanged lane material and retain scoped overrides.
A new role label alone does not trigger a level change. Use
`--refit-levels` to recalculate levels for the lanes being refitted.
Standalone voice and chained runs report changed variants and gains.

For quick edits, use `reconstruct run --from roles WORK_DIR --keep-voicing`
or add `--keep-voicing` to `reconstruct rerun`. It reuses compatible lane
settings, lists each stale part gain or instrument it removes, and fits
settings when a new lane or instrument needs them. MCP resumed runs accept
`options.keepVoicing`.

```sh
java -jar "$CLI" reconstruct audition "$work" --lane FM1 --start 45.3 --end 48.8 \
  --variant "$current" --variant "$candidate" --solo --reference "$mix" "$work/phrase.wav"
```

Audition uses the last assembled arrangement and applies each candidate to
one FM lane for the selected phrase. Use source seconds by default or
`--time project` for project seconds. Substitute `--note TRACE_ID` for the
span to use the assembled voiced gate and infer the FM lane. The output
reports both clocks and project ticks. Playback warms up from the song start.
In-mix playback is the default. `--gain` multiplies the lane's existing level
within the phrase. Repeat `--variant` for up to 32 candidates, or use
`--shortlist` for the saved candidates on the explicit or inferred lane.
With multiple variants the command writes `phrase-1.wav`, `phrase-2.wav`,
and so on. The table shows the variant ID, filename and
successive RMS bins in dBFS before optional reference level matching.
`--rms-json FILE` saves the paths, IDs and linear RMS bins to a new JSON
file. Warped projects can take the original mix or the saved steady reference.
In MCP, `reconstruct_audition` takes a `variants` array and optional `rmsJson`
path. It also returns these measurements inline and accepts `note` and `time`.

Set a lane-wide patch and fit its level with
`reconstruct voice WORK_DIR --lane FM1 --variant ID`, then assemble. To
apply a candidate to a passage, add an entry in the target lane's `overrides` array in
`voicing.json`, then run `reconstruct assemble WORK_DIR`. Scope entries use
project seconds, project ticks, and/or allocated note IDs, and can set a
variant, gain, gate, release floor or vibrato. See the artifact contract for
field names and boundary behavior. [Staged reconstruction](../docs/architecture/reconstruction-stages.md#voice)

### Inspect the score and plan musical roles

```sh
java -jar "$deck_jar" reconstruct describe full-song
java -jar "$deck_jar" reconstruct describe full-song --section S3 --parts 0,3 --bars 17-24 --json
java -jar "$deck_jar" reconstruct plan-template full-song --out full-song/roleplan.json
# Edit full-song/roleplan.json before rebuilding.
java -jar "$deck_jar" reconstruct run --from roles full-song --keep-voicing
```

Run these commands after the structure stage. The score view uses the
structure stage's notes, including applied corrections. Its header gives the
selected tempo, time base and prepared note count for each part. Section
ranges and the grid use project seconds when warped and source seconds
otherwise. JSON output includes the full section start and end times. Each
part summary gives pitch range, notes per bar, peak simultaneous notes, any
recurring one- or two-bar figure, and single/chord attack counts. Each
occupied grid step counts once. Each JSON section's flat `attacks` array
gives two numbers per listed part, single then chord, in part order. Role
counts appear when `roles.json` is available. A repeated section
references an earlier section and prints the differing parts. Use `--section`
or `--bars` to expand repeats. Output goes to stdout by default. Use
`--out FILE` to create a new file.

Each bar shows 16 equal subdivisions. `C4` is MIDI 60, `.` is a rest,
and `-` is a sustain. A number after a rest or sustain is its run length,
so `.16` is an empty bar. Brackets group attacks quantized to the same step,
for example `[C4+F#4]`. When a step has both a new attack and a held note,
it shows the new attack. Percussion lines show hits such as `K` for kick
(GM 36), `S` for snare (38), `H` for closed hi-hat (42), and `D<n>` for
other unmapped GM hit numbers. The grid is for inspection and does not
change event times.

`plan-template` prints a JSON template with each active section and part's
most frequent heuristic role and uses source seconds for any added time bounds. Use `--out full-song/roleplan.json` to save it
without overwriting an existing plan. Edit the template to express musical intent. Example plan:

```json
{
  "version": 1,
  "timeBase": "source",
  "rules": [
    {"sections": ["S1", "S3"], "part": 3, "role": "melody", "voice": "top"},
    {"sections": ["*"], "part": 0, "role": "harmony"},
    {"sections": ["S3"], "part": 0, "role": "melody", "voice": "top", "texture": "single"}
  ]
}
```

Rules refer to the displayed section IDs and one-based inclusive bar
numbers. Write a bar range as a string such as `"bars": "6-7"`. Use
`sections`, `bars`, or both to match their intersection.
Rules match the note's attack and optional inclusive MIDI pitch bounds
and `texture`. Each attack belongs to the nearest displayed 16th step in
its bar, with rounding kept within that bar. An attack is `chord` if at
least one other note in the same part attacks in the same cell (the
bracketed groups in the score). Otherwise it is `single`. Simultaneous
attacks in other parts and notes already sounding do not count. All roles
accept the filter. Use `any` or omit it to allow both textures. Texture
and pitch filter candidates before `top` or `bottom` traces a line.
Use `startSeconds` (inclusive) or `endSeconds` (exclusive) to place a handoff
inside a bar. Set `"timeBase":"source"` for source seconds or `"timeBase":"project"`
for project seconds. A rule with only time bounds can omit sections and bars
and defaults to source seconds. Existing section- or bar-scoped rules without
`timeBase` still inherit the clock in `describe`. The roles stage reports both
time spans and the resolved tick bounds. The last matching rule wins. Roles are `melody`, `counter`, `bass`,
`harmony`, `drums`, and `drop`. For melody, counter and bass, `top` or
`bottom` traces one line from eligible notes, with other eligible notes
left in harmony. Melody and counter default to `top`, bass to `bottom`.
Use `all` to assign every match directly. Harmony still goes through
reattack thinning. A `melody` rule takes precedence over automatic FM1
selection across its section, bar and time scope, including during rests.
Pitch and texture filters limit the rule's melody candidates. Unmatched
automatic melody notes in scope become harmony. Other traced roles keep
their existing bar-local precedence. Affected decisions carry a zero-based
`roleplan#N` in their reason.

For a song-wide lead, pass `--lead-part PART` to staged `reconstruct run` or
`reconstruct transcribe`. Use the non-drum MIDI part ID shown by `describe`.
The choice persists in `score.json` for resumed stages. Backing parts remain
available to allocation. A scoped roleplan can hand FM1 to another part.
`--stem PART=WAV` supplies audio evidence without reserving FM1.

The MCP server includes `reconstruct_describe` with the
same filters and `reconstruct_plan_template`. Use `artifact_read` and
`artifact_write` with artifact `roleplan` to inspect or validate edits, then
call `reconstruct_run` with `from` set to `roles`.

### Describe copied note IDs

```sh
java -jar "$deck_jar" reconstruct describe "$work" --notes note-1,note-2 --context 1bar --json
java -jar "$deck_jar" describe-notes song.smps.json --sequence lead --notes note-1 --context 1bar
```

Note mode accepts comma-separated IDs or a JSON array. It returns the
selected notes and their parts' notes in the selected bars plus the requested
number of following bars (default one, from `0bar` to `16bars`). Read the
trace for dropped, truncated or reassigned notes and the competitors it
identifies. With `--revision HASH`, the result reports whether the live hash
differs. MCP `reconstruct_describe` accepts `notes`, `context` and `revision`
with `workDir` and `json: true`. The arrangement command accepts a JSON array
of placement IDs via `--clip` and returns JSON.
[Studio handoff format](../docs/user-guide/agents.md#investigate-notes-pasted-from-studio)

For a screenshot lasso attachment, run
`reconstruct describe --from-png IMAGE --json` or pass `fromPng: "/absolute/path"`
and `json: true` to `reconstruct_describe`. The result contains the copied
payload and names the decode path (`metadata`, `lsb` or `registry`).
PNG and JPEG attachments can fall back to the footer's local registry.
[Read lasso attachments](../docs/user-guide/agents.md#decode-a-screenshot-lasso)

### Inspect time-aligned evidence

For stem-derived scores, `stem-upper-harmonic` in the note trace identifies
a generated upper candidate excluded by harmonic phase and envelope
evidence. The score retains its ID in `reductions`. Replay from
`transcribe` to apply the check to a saved transcription.

```sh
java -jar "$deck_jar" reconstruct view "$work" --start 45.3 --end 48.8 \
  --stem 0 --render --harmonics "$note_id" "$work/evidence.png"
```

The command creates a new PNG and prints a JSON legend. MCP
`reconstruct_view` takes `workDir`, `start`, `end`, and a new `output` PNG
path inside the work directory. It returns the image plus the same legend.
The legend connects each `n…` label to its trace ID, layer, part, lane, pitch,
fate, source and project seconds, and 60 Hz ticks. A pending insertion has
no trace fate until it reaches a stage. Assembly-only events such as echo
copies appear without a source part or trace fate when there is no part filter.
The legend also records the lane patch changes.

- `--time source|project` selects the input bounds and linear x-axis,
  defaulting to source seconds. Warped views label both clocks.
- `--stem PART` selects a zero-based mapped part. By default the view uses
  the mapped FM1 lead in the passage, with a fallback to the configured lead
  if no FM1 note overlaps, then to the steady reference or source mix.
  `--wav FILE` overrides stem selection with an aligned source-time WAV.
- `--render` runs the assembled full mix from time zero and displays its
  spectrum alongside the source. Each panel scales the brightest energy
  in its displayed range to white and clips below its top 30 dB, so compare
  pitch and timing across panels.
- `--pitch-min P --pitch-max P` sets inclusive MIDI pitch limits. Omit the
  limits to show the selected notes in the span with three semitones of
  padding. `--harmonics NOTE_ID` marks integer frequency multiples during
  the selected note and expands the default upper bound for the first six
  multiples when visible below Nyquist. `--harmonic-sum` optionally emphasizes
  supported fundamentals in the source.
- `--notes FILE` overlays unmapped source-time evidence from the same
  single-melodic-part MIDI or JSON `[[start, end, pitch], ...]` input accepted
  by `transcribe --add-notes`. The overlay is independent of trace filters.
- Repeat `--harmonics-at PITCH[@TIME]` to mark harmonics from a MIDI pitch
  or note name (for example, `D#4@35.4`). Time uses the selected clock.
  Omit it to mark the pitch's harmonics across the whole span.
- `--ridges` writes `OUT.ridges.json` with the top three interpolated spectral
  peaks per column for the source and, with `--render`, the chip panel.
  `--ridge-count N` enables the export and sets the count to 1–32. Points
  carry both clocks, fractional MIDI pitch, and spectral dBFS. With
  `--harmonics NOTE_ID` or `--harmonics-at PITCH[@TIME]`, the export also
  tracks the fundamental, second and third partial separately.
- `--db` shows each panel's peak spectral dBFS and plots the fundamental's
  absolute level over time for any selected note or pitch. The legend gives
  the full numerical traces. `--shared-db` maps both spectrograms to the same
  30 dB window, using the higher peak as the ceiling.
- Bends available in the stage artifacts are drawn on their gates. The
  assembled curve comes from rendered FM frequency or PSG period writes.
- When there are simultaneous DAC losses, the legend and plot show the
  free PSG3 intervals at allocation. See the [hat recipe](../docs/architecture/reconstruction-stages.md#tune-or-override-psg3-hats).
- `--force` replaces the PNG and any requested ridge JSON with staged exports.
  `--pitch-preset drums` uses MIDI 35–81. Explicit pitch limits take precedence.
- `--lanes FM1,PSG1 --parts 0,3` filters the overlays and lane rows. The
  source spectrum still shows the selected audio. With `--render`, `--lanes`
  also solos those lanes in the chip panel, so it compares against a stem
  rather than the full mix. `--width PX` defaults to 1440
  (640–2400). A view can span up to 60 seconds in either clock. Use a short
  span to resolve closely spaced attacks.

MCP uses corresponding fields `time`, `stem`, `wav`, `render`, `pitchMin`,
`pitchMax`, `lanes` (array), `parts` (array), `harmonics`, `harmonicSum`,
`width`, `notes`, `harmonicsAt` (array), `ridges`, `ridgeCount`, `db`, `sharedDb`,
and `pitchPreset`.

### Persistent musical edits

```sh
java -jar "$jar" reconstruct edit "$work" add   '{"id":"answer","reason":"second attack audible in stem","op":"insertNote","part":0,"start":12.5,"end":12.8,"pitch":68,"lane":"FM1","role":"melody"}'
java -jar "$jar" reconstruct run --from transcribe "$work"
java -jar "$jar" reconstruct edit "$work" add --file batch.json
java -jar "$jar" reconstruct edit "$work" add --dry-run '{"id":"register","reason":"raise the lead","op":"transpose","noteIds":["NOTE_ID"],"semitones":12}'
java -jar "$jar" reconstruct edit "$work" add --dry-run '{"id":"cut","reason":"mark the second attack","op":"splitNote","noteId":"NOTE_ID","at":12.75}'
java -jar "$jar" reconstruct edit "$work" add --dry-run '{"id":"sustain","reason":"join repeated attacks","op":"mergeNotes","part":0,"pitch":64,"span":[15.2,16.8]}'
java -jar "$jar" reconstruct edit "$work" add --dry-run '{"id":"backing","reason":"use PSG tone","op":"pinLane","noteIds":["NOTE_ID"],"laneType":"PSG"}'
java -jar "$jar" reconstruct edit "$work" list
java -jar "$jar" reconstruct edit "$work" remove answer
```

Keep musical changes in `edits.json` and propose by default when a human
is reviewing. Inline `edit add JSON` takes one intent object. A batch is an
ordered array of intents or a versioned edits object. The CLI returns the
first affected stage as `from`. MCP `reconstruct_edit` accepts
`workDir`, `action: "add" | "list" | "remove"`, and an `edit` object or
`edits` array for add, or an `id` for remove. Set `rerun: true` on MCP add
or remove to rebuild once and get a `traceDiff`. Check `lastRun` for invalid
or orphaned targets and blocked pins. Use `add --dry-run JSON` or MCP
`dryRun: true` to see matched IDs, incoming and resulting roles, and whether
each transition is already satisfied. Edit times default to source seconds;
set `"timeBase":"project"` on an intent for project bounds. An upstream rebuild reapplies the
intents, and manifest replay copies them into the new work directory.

Use `arpeggio` or `chordInstrument` to fit selected co-attacking tones
on one lane. Both resume from `allocate`, and take `noteIds` or `part` with a
numeric or named section span. See
[one-channel chord voicing](../docs/architecture/reconstruction-stages.md#one-channel-chord-voicing)
for examples, tuning limits and byte cost reporting.

See the [operation fields](../docs/architecture/reconstruction-stages.md#persistent-musical-edits)
for selectors, role swaps, velocity spans, extension guards and other edits.

### Review edit proposals

```sh
java -jar "$jar" reconstruct edit propose "$work" --file batch.json
java -jar "$jar" reconstruct proposals list "$work" --group phrase-1
java -jar "$jar" reconstruct run "$work" --with-proposals --id answer
java -jar "$jar" reconstruct proposals revise "$work" answer --file revised.json
java -jar "$jar" reconstruct proposals accept "$work" answer answer-2
java -jar "$jar" reconstruct proposals reject "$work" --group phrase-1 --op insertNote
java -jar "$jar" reconstruct proposals list "$work" --show-decided
```

`edit propose WORK_DIR JSON` also takes a single intent. Set
`"group":"phrase-1"` to review intents together. Optional
`"evidence":{"text":"Separate attack in the stem","path":"evidence.png"}`
links a view or render inside the work directory. Studio's **Proposals**
panel supports selecting several rows, revising a pending intent, and
showing decisions. Revisions replace the intent while preserving its ID and
default group.

Accept and reject take IDs, `--file` (a JSON array of strings or a
whitespace-separated list), or the `--group` and `--op` selectors. MCP
`reconstruct_edit` exposes `id`, `ids`, `file`, `group`, `op` and
`showDecided`. Use `action: "revise"` with `id` and the replacement
`edit` object. Accept validates the selected set in journal order once
and records it as a batch if it resolves. Rebuild from the returned `from`
stage. Reject records the decision without rebuilding. Use `--verbose`
(MCP `verbose: true`) to include the full acceptance preview.

A `transpose` intent adds a signed, nonzero integer `semitones` offset to
each stable score ID in `noteIds` and rebuilds from `transcribe`. Offsets
compose in journal order. The optional `laneType` checks the target's
`FM` (default) or `PSG` tone range. PSG pitches above the FM range require a
PSG tone lane. The intent skips drum parts and refuses the batch if a tonal
target would exceed its range. MCP `reconstruct_edit` accepts the same
intent in `edit` or `edits`.

`reconstruct edit WORK_DIR add --file BATCH` and `--dry-run` return one
result per intent. `applied` and `satisfied` describe the journal's
resolution on preview or rebuild. A `rejected` intent carries the
validation reason. The `journalSaved` field reports whether a direct batch
was recorded for future rebuilding, including well-formed intents that
could not resolve against the current score. A batch with malformed intents
is left unsaved. Use `--verbose` for note-level outcomes and markers.

Preview all proposals by omitting the filter, or use `--group GROUP`. The
accepted arrangement must have a `render.wav`. The command creates a new
sibling preview directory and returns its path and A/B WAVs. Each WAV plays
the built render followed by the proposal render at the same project times.
Long spans are split into excerpts of up to 60 seconds per side. If no target
resolves there is no A/B span. Accept changes the journal and returns the first
stage to rebuild. Reject removes the selected proposals. Without an ID or
group, accept and reject act on all proposals.

In MCP, use `reconstruct_edit` with `propose: true` and `edit` or `edits`.
Use `action: "proposals"`, `"accept"` or `"reject"` with an optional `id` or
`group`. Use `reconstruct_run` with `workDir`, `withProposals: true` and,
optionally, `proposalId` or `group` to render the sibling preview.

### Per-note trace and replay

Studio hand edits set `detachedFromReconstruction` in `arrangement.smps.json`.
A resumed `reconstruct run` or `assemble` rejects a detached arrangement
unless given `--overwrite-detached`. `reconstruct rerun WORK_DIR NEW_WORK_DIR`
leaves the original arrangement in place. To rebuild in place using the
current score inputs, pass the same directory twice and add
`--overwrite-detached`. In MCP, use `options.overwriteDetached` for
`reconstruct_run` or `reconstruct_stage`, and `overwriteDetached` for
`reconstruct_rerun` (omit `output` for in-place rebuild).


```sh
java -jar "$deck_jar" reconstruct trace full-song --start 45.5 --end 46 --part 0 --pitch 68
java -jar "$deck_jar" reconstruct trace full-song --by-onset --start 45.5 --end 46 --part 0
java -jar "$deck_jar" reconstruct trace full-song --lane FM1 --status shortened --json
java -jar "$deck_jar" reconstruct trace-diff before after --span 45.5,46 --json
java -jar "$deck_jar" reconstruct rerun full-song replay --search relocated-inputs
```

Trace selects notes by source interval, zero-based part and MIDI pitch.
`--by-onset` gives one line per part and 20 ms attack cluster with pitch,
role, lane, fate, ID and both clocks. Use `--time project` for project
intervals. MCP exposes the same options as `byOnset` and `time`.
Use either `--pitch` or an inclusive range with `--pitch-min` and
`--pitch-max`. `--lane` selects the allocated lane. Valid values for
`--status` are `kept`, `moved`, `shortened`, `merged`, and `dropped`.
The trace shows each stage's version of the note, including its reason and
competing note IDs. Compare the result with the accepted edit journal.
The diff compares stable IDs and sets `outsideSpan` when neither version
of the note touches the specified source-time interval. It groups contiguous
lane moves outside that span in `lanePermutations`, with the first diverging
IDs and suggested `pinLane` edits. `voicingChanges` lists lane variant,
gain and scoped override changes. `compiledTimeline` counts changed events
outside the span, with the first project tick and affected lanes.
`renderedPcm` counts differing saved PCM frames, including effects carried
over from earlier audio. When matching-length reconstruction WAVs differ
after `--span`, `renderedPcm.psgTail` re-renders the arrangements with each
PSG lane soloed. It reports the first changed sample frame, total changed
frames and cause for each differing PSG lane. A tonal PSG note can start in
a different oscillator phase even when its pitch, level and compiled
events match.

MCP `reconstruct_trace` returns JSON and accepts `workDir`, `start`,
`end`, `part`, `pitch`, `pitchMin`, `pitchMax`, `lane`, and `status`.
`reconstruct_trace_diff` accepts `oldWorkDir`, `newWorkDir`, and optional
`span` as a comma-separated source-time interval.

A staged run writes a private `manifest.json` containing the build version
and commit, reconstruction options, part-to-stem mapping, and input
basenames, paths, and SHA-256 hashes. The work directory retains copies of
external transcriber outputs, imported MIDI/JSON notes, and candidate JSON
under `snapshots/`. Rerun checks the original path and retained copy
against the hash, then searches any supplied directories recursively for
matching bytes. It reports all unresolved inputs together and starts in
a new directory using the current build. It replays the original inputs
and options with the saved external notes, musical edits, role plan,
section journal and voice journal.
The manifest records the plan hash. Replay also reuses the saved clip
anchors and allocation affinity seed. Use `--keep-voicing` for accepted
voicing settings. Keep the recipe and snapshots with the private work
directory. Both `run` and `rerun` summarize invalid and orphaned edits
on stderr.

## Deliberately limited input contract

The CLI accepts a finite subset of standalone S3K SMPS. The arrangement
compiler packs repeated timed events into counted loops and shared
subroutines. Use `compile-project PROJECT OUTPUT --linear` to emit the
original linear encoding.

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
  Counted loops (F7) use counter zero, a nonzero finite count, and a backward
  pointer. Calls (F8) target routines after the track stop that end in a
  return (F9). Control flow is flat and timed events have explicit durations.
  Unconditional jumps, direct register writes and other unlisted commands
  are rejected.

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

Run the headless build gate and smoke-test the packaged MCP server. Set
`scratch` to a writable directory outside the checkout.

```sh
mvn -B -pl headless -am verify
python3 -I tools/mcp_smoke.py headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar --scratch "$scratch"
```

For title-music and demo-timeout checks on personal-use patched ROMs, see the [headless ROM probe for BizHawk and libretro](../tools/rom-probe/README.md).

## External transcription

On the MIDI route, the supplied MIDI provides the source notes by default.
To replace selected melodic parts with an external transcriber, supply an
aligned WAV stem and a role for each part ID.

```sh
java -jar "$CLI" reconstruct run song.mid mix.wav catalogue.json external-work \
  --stem 0=lead.wav --transcriber muscriptor-medium --transcribe-part 0=lead
```

To add an aligned tonal stem that has no MIDI part, use a configured
polyphonic transcriber and repeat `--transcribe-unmapped ROLE=WAV`:

```sh
java -jar "$CLI" reconstruct run song.mid mix.wav catalogue.json work \
  --transcriber muscriptor-medium --transcribe-unmapped keyboard=keyboard.wav
java -jar "$CLI" reconstruct import DOWNLOAD IMPORT_WORK --catalogue catalogue.json \
  --transcribe-unmapped muscriptor-medium --run
```

Import selects unmapped tonal stems, including those with an
`also_correlates_with_part` warning. It leaves vocals, FX, drums, percussion,
and nearly silent stems out of automatic transcription.
For MCP, pass `transcribeUnmapped: "muscriptor-medium"` on `reconstruct_import`, or
`options: {transcriber: "muscriptor-medium", transcribeUnmapped: {"/path/to/keys.wav": "keyboard"}}`
on a fresh `reconstruct_run`. The import response includes the options in its
`runRequest`.

The CLI reads `$XDG_CONFIG_HOME/opensmpsdeck/transcribers.json` (default
`~/.config/opensmpsdeck/transcribers.json`). Set `OPENSMPSDECK_TRANSCRIBERS`
or pass `--transcriber-config FILE` to select another file. Its JSON object
maps names to executable argument arrays. Commands run directly with
`{input}` and `{output}` substituted for the absolute stem and output paths,
`{role}` for the requested role, and `{device}` for the device selected by
`--transcriber-device` (default `auto`). Use an executable and arguments you
trust. The command writes a JSON note array to the output path and can write
a `<output>.meta.json` object with tool, model, version, prompt, and other
provenance. See the [MuScriptor adapter](../tools/transcribers/muscriptor/)
for setup and an example configuration.

Use `--replace-notes 0=lead.mid` or `--add-notes 1=keys.json` to import a
single melodic MIDI part or JSON `[[start, end, pitch], ...]` directly.
Repeat the options for different parts. Times are in source seconds.
JSON uses velocity 96. Notes outside the selected interval or the S3K
melodic pitch range are omitted, and releases are clipped to the interval.
An empty replacement clears the selected part. Drums, uncovered parts,
and the original tempo analysis continue to use the supplied MIDI. A
missing or failed command aborts the requested run.

For MCP, call `transcribers_list` and pass
`options: {transcriber: "muscriptor-medium", transcribeParts: {"0": "lead"}}` to
`reconstruct_run` or a fresh `reconstruct_stage` for `transcribe`. Include
the stem in `inputs.stems`. The server uses its own local configuration.
The score embeds the imported notes and provenance, including file hashes,
and the report includes per-part provenance. `run --from transcribe`
reapplies the saved notes without invoking the external command.

### Audio-only reconstruction

Give the CLI a mix and already aligned, labelled WAV stems:

```sh
java -jar "$CLI" reconstruct run --audio-only mix.wav catalogue.json work \
  --stem drums=drums.wav --stem bass=bass.wav --stem vocals=vocals.wav \
  --stem guitar=guitar.wav --stem piano=piano.wav --stem other=other.wav \
  --transcriber muscriptor-medium
```

Audio-only mode selects the sole configured tonal transcriber automatically.
With multiple transcribers, configure a default for each active role or specify
`--transcriber NAME` as the global override. Repeat `--transcriber-for ROLE=NAME`
to override individual roles on `run`, `transcribe`, or audio-only `import`.
Role overrides take precedence over the global override. MIDI-based runs
also accept role selection for explicitly requested external parts and stems.
Stems with no audible signal in the requested interval are skipped.
Each remaining tonal stem supplies a part. Vocals default to a monophonic
lead and use the transcriber's `vocals` prompt. Bass, guitar, piano, synth,
keyboard and other keep their corresponding roles. Use
`--stem-role LABEL=ROLE` to override a label's role, for example
`--stem-role synth=lead` for an instrumental track.

The following `transcribers.json` combines
[MuScriptor Medium](../tools/transcribers/muscriptor/),
[Basic Pitch](../tools/transcribers/basic-pitch/),
[RMVPE](../tools/transcribers/rmvpe/) and [ADTOF](../tools/transcribers/adtof/).
Install the adapters and replace the paths. Each command may choose its own device.

```json
{
  "muscriptor-medium": [
    "/path/to/muscriptor/venv/bin/python", "/path/to/repo/tools/transcribers/muscriptor/run.py",
    "--input", "{input}", "--output", "{output}", "--role", "{role}", "--device", "{device}",
    "--model", "medium", "--weights", "/path/to/muscriptor/weights/medium/model.safetensors"
  ],
  "basic-pitch": [
    "/path/to/basic-pitch/venv/bin/python", "/path/to/repo/tools/transcribers/basic-pitch/run.py",
    "--input", "{input}", "--output", "{output}", "--role", "{role}"
  ],
  "rmvpe": [
    "/path/to/rmvpe/venv/bin/python", "/path/to/repo/tools/transcribers/rmvpe/run.py",
    "--input", "{input}", "--output", "{output}", "--role", "{role}", "--device", "{device}",
    "--source", "/path/to/rvc", "--weights", "/path/to/rmvpe.pt"
  ],
  "drumTranscribers": {
    "adtof": [
      "/path/to/adtof/venv/bin/python", "/path/to/repo/tools/transcribers/adtof/run.py",
      "--input", "{input}", "--output", "{output}", "--device", "{device}"
    ]
  },
  "defaults": {
    "bass": "muscriptor-medium",
    "guitar": "muscriptor-medium",
    "piano": "muscriptor-medium",
    "other": "muscriptor-medium",
    "synth": "muscriptor-medium",
    "vocals": "muscriptor-medium,basic-pitch,rmvpe",
    "drums": "adtof"
  }
}
```

With an adapter named `rmvpe` configured, audio-only runs select its
syllable-aware transcription for vocals by default. Use the
`vocalLead` object in `transcribers.json` to choose another method or
change the vocal register:

```json
"vocalLead": {"choice": "auto", "registerShift": 12}
```

`choice` accepts `auto`, a configured adapter name, or `fusion`. Fusion
uses at least two note adapters listed in `defaults.vocals`. For an
individual run, pass `--transcriber-for vocals=NAME` or MCP
`transcriberFor.vocals`. A comma-separated list runs up to three candidates,
including RMVPE if listed. The comparison measures audible frame coverage
and one-semitone F0 agreement. With an RMVPE contour available, stem
periodicity supplies the comparison reference. The manifest and report
record the selected method, register shift, gate and candidate metrics.

The syllable mode preserves separate attacks on repeated pitches and
snapshots its pitch contour and detected boundaries. Alternative note
transcribers use the existing monophonic cleanup and pitch salience.
When other tonal stems are present, vocals need at least two seconds of
audible periodic pitch (or 15% of a short excerpt, whichever is smaller)
and an RMS level of at least 3.5% of the strongest other tonal stem.
Residual vocals are skipped and normal lead selection resumes.
Use `--lead-part` on a direct run to pin the vocal part explicitly.
The vocal-derived FM1 part uses a bright sustained patch selected from the catalogue by a
held-note probe. When the stem supports a vibrato estimate, it is encoded
using the usual FM modulation controls. Edit the source instrument and its
effects in `voicing.json` to override these choices.

Review the saved vocal contour and suggested edits by phrase with
`reconstruct vocal-review WORK_DIR [--phrase ID] --json` or MCP
`reconstruct_vocal_review`. The response includes source-time syllables,
per-note RMVPE and stem harmonic summaries, repeat votes, and kept FM1
gates in both clocks. `--propose` (MCP `propose: true`) saves suggestions
for CLI or Studio review. Vocal decisions follow stable note identities and
the intent and material evidence, so a rejected suggestion stays out of
pending counts across rebuilds. Use `--show-decided` (MCP
`showDecided: true`) to include decision history.

`reconstruct vocal-render WORK_DIR --phrase ID OUT.wav` (MCP
`reconstruct_vocal_render` with `workDir`, `phrase` and `output`) plays
the built FM1 lane from the beginning and writes just the chosen phrase.
The JSON compares source-time syllables with detected render attacks within
50 ms, measures stem-voiced seconds below the render's activity threshold,
and reports the fraction of audible, placed FM1 gate time within 0.7
semitones of the register-shifted stem contour. Envelope measurements can
miss legato reattacks. Pitch agreement uses saved gate pitches and RMVPE,
with independent octave evidence available in vocal review.
See [Clean up a vocal-derived lead](../skills/opensmpsdeck/SKILL.md#clean-up-a-vocal-derived-lead).

A `drums` stem feeds DAC and PSG3 routing. When the stem is absent, detection
uses drums from local separation, or the mix if the separator is unavailable.
External drum commands
write `[[seconds, gmPitch, velocity], ...]` and may supply a
`<output>.meta.json` sidecar. The built-in `spectral` detector is the default
when no drums default is configured. Select a detector with
`--drum-detector NAME` or `--transcriber-for drums=NAME`.

Drum or mix attacks determine the beat grid. Tracking ranks local onset fits,
a kick/snare periodicity prior and a soft preference for 70–180 BPM.
`score.json` records competing tempo hypotheses. Override the grid with
`--tempo BPM --bar-offset SECONDS` and control warping with `--steady-tempo`.
The score and manifest save detector provenance, hits and tonal transcription
snapshots for replay. If the native bank overflows after optional bends and
copies are removed, assembly progressively omits lower-priority automatic
harmony and counter attacks. The report records each omission. An oversized drum-balance probe keeps
the initial fitted levels and leaves bank fitting to assembly.

For a folder containing `mix.wav` and labelled stems at its root or under
`stems/`, use:

```sh
java -jar "$CLI" reconstruct import STEM_FOLDER IMPORT_WORK --audio-only \
  --catalogue catalogue.json --run
```

Omit `--run` to check the folder and get a runnable request. In this command,
`--stem-role` uses the folder-relative file name, such as
`--stem-role stems/synth.wav=lead`. Import excludes separately labelled FX
and percussion by default. Use an explicit role override to include one.

In MCP, send `inputs: {audioOnly: true, wav, catalogue, stems}` to
`reconstruct_run`, where `stems` maps stem labels to absolute WAV paths.
Put label-based `stemRoles`, `transcriberFor` (for example
`{"vocals":"basic-pitch"}`), `transcriber` and `drumDetector` in `options`.
For `reconstruct_import`, put those options and `audioOnly` at the top level
and use file-based `stemRoles`. The MCP server reads its own local configuration. Its `runRequest` can be passed to
`reconstruct_run` once a catalogue is supplied.

## Arrange for cleanliness

Save the rules you want under `arrangement` in the work directory's
`voicing.json`. Use part IDs from `reconstruct describe WORK_DIR`. MCP
`artifact_read` can fetch the current voicing. Add the object below as its
`arrangement` member and submit the whole result through `artifact_write`
with `artifact: "voicing"`. The rules persist through voice refits,
staged rebuilds and manifest replays. Omit any control you do not use.

```json
{
  "partChips": [{"part": 3, "chip": "FM", "lanes": ["FM3", "FM4", "FM5"]}],
  "polyphony": [{"part": 3, "maxVoices": 3, "span": [8, 24]}],
  "bassHarmonics": [{"part": 3, "bassPart": 1, "intervals": [12, 19, 24],
                    "pitchTolerance": 0, "minOverlap": 0.6, "minLevelDb": 6}],
  "hats": "all",
  "drumDynamics": {"part": 0, "levels": true, "dropBelowDb": -15},
  "sectionFit": [{"part": 3, "maxJumpDb": 3}]
}
```

- **Part chip.** Choose `FM` or `PSG`, optionally with a subset of its tonal
  lanes and a source-time `span`. The last matching chip rule applies at each
  attack. An incompatible pitch or conflicting explicit lane/voice assignment
  is reported as a dropped note. Explicit layers are separate from dry placement.
- **Polyphony.** Cap simultaneous source gates at hardware tick resolution.
  The cap counts held notes as well as new attacks. At one voice it preserves
  the top note. At two or more it preserves top and bottom while preferring
  stronger, longer inner notes. It drops whole notes, including a sustained
  note crossing the start of an optional span if it conflicts there.
- **Bass partials.** `bassPart` defaults to `part`. Choose another part ID
  to use a separate bass line. Defaults are intervals 12, 19 and 24,
  exact semitone alignment, and 60% overlap of the candidate's gate.
  `pitchTolerance: 1` allows a one-semitone miss. When both parts have aligned
  stems, compare fundamental levels in the overlap (`minLevelDb`, default 6).
  Otherwise compare velocities (`minVelocityDelta`, default 10).
- **Hats.** `all` sends GM 42, 44 and 46 to PSG3 noise. `overflow` sends
  only hats rejected by simultaneous DAC hits or protected DAC tails.
  PSG3 collisions with explicit placements or other simultaneous hats can
  still drop a hit. These modes require PSG to be enabled.
- **Drum dynamics.** Map an isolated drum stem and choose its part, optionally
  restricting attack times with `span`. Attack band RMS is measured relative
  to the median for the drum class in the selected scope. `dropBelowDb`
  deletes hits below that relative level. `levels: true` adjusts kept DAC
  and PSG hits in 2 dB increments between −10 and +2 dB relative to the
  existing class level. A DAC `drumGainOverrides` entry takes precedence.
  S1/S2 stock DAC samples retain their stock levels.
- **Section fit.** Map a stem for each listed tonal part. Assembly probes the
  built part across its lanes, including layers, and measures level error over
  active stem frames within effective sections. It limits fitted adjustments
  to ±12 dB and limits the change between sections to `maxJumpDb`. Each new
  attack takes the gain for its section. Unmeasurable sections carry the
  previous adjustment (zero if there is none). Consult `report.json.sectionGains`
  for measured and applied dB values, and `drumHitGains` for hit multipliers.

Spans are half-open `[start, end)` in source seconds. Rebuild with
`reconstruct run --from allocate WORK_DIR` after changing allocation controls
or the drum drop threshold. Use `reconstruct assemble WORK_DIR` for
section fit or level-only changes. The MCP equivalents are `reconstruct_run`
with `from: "allocate"` and `reconstruct_stage` with `stage: "assemble"`.
Inspect `reconstruct trace WORK_DIR --part ID --json` and the report's
`reductions` for dropped IDs, reasons and allocation blockers.

For stem-backed holds, describe and trace flag missing or shortened gates
and gates with much less tail energy than the source. Use
`reconstruct proposals list WORK_DIR --group held-notes` to review the
findings and suggested fixes. These are review suggestions supported by
the assembled render.

For an aligned solo of a built part, run
`reconstruct part-render WORK_DIR --part ID OUT.wav` or use
`reconstruct_part_render` with `workDir`, `part` and `output`.
The WAV plays from zero on the project clock, including PSG spills and
layers on any lane. Its JSON identifies the lanes and the mapped stem
if available. Use the saved time map to compare project time with source
time if steady tempo is active.

For a vocal transcribed at another register, set `vocalLead.registerShift`
in `transcribers.json` on the fresh run. Review uses the saved semitone
shift, including values such as +24. If you transpose already-transcribed
notes through edits, set `arrangement.vocalRegisterShift` in `voicing.json`
to tell review their new register.

For a non-vocal monophonic stem, add a rule by stem label in
`transcribers.json` and use the usual audio-only CLI or MCP run/import:

```json
"monophonicStems": {
  "guitar": {"adapter": "rmvpe", "span": [8, 24], "registerShift": 12}
}
```

Use a configured F0 adapter that emits notes and a `f0Cents10ms` sidecar
such as the RMVPE syllable adapter. The adapter receives the `vocals` prompt
and runs against the aligned stem. The arrangement part retains its own
role. Outside the optional span the normal polyphonic adapter still
supplies notes. Omit the span to use F0 for the entire stem. The default
register shift is zero. The run captures the transcriptions for replay.
Use the role plan to place a solo span on the lead lane when needed.

### Shortlist sustained FM patches

Combine role and held-note filters before auditioning. FM role hints
`lead`, `bass` and `backing` use observed playback on FM1, FM2 and FM3–5
respectively. They supplement existing catalogue tags and work with
existing indices. For example:

```sh
java -jar "$CLI" catalog search catalogue.json --kind fm-voice --role lead \
  --min-centroid-hz 500 --max-centroid-hz 2600 --max-decay-db 4
```

The MCP `catalog_search` equivalents are `role`, `minCentroidHz`,
`maxCentroidHz` and `maxDecayDb`. With any acoustic filter, search probes
raw FM voices at A4 for 0.8 seconds and returns `heldNote.centroidHz`
and `heldNote.decayDb`. Decay is the early RMS level minus the late
held RMS level in dB, so smaller values favor sustain. The centroid is
measured on the held portion of the note. Filtering happens before pagination.

## Source voices

Use `reconstruct voices WORK_DIR` to list identified instruments, or append
a `create`, `rename`, `merge`, `split`, `move` or `assign` payload to the
command. The journal is replayed by `reconstruct run --from voices WORK_DIR`.
Assignments follow notes across lanes. Use `view --color-by voice` for the
color overlay. See the [voices stage](../docs/architecture/reconstruction-stages.md#identify-and-assign-voices)
for operation payloads, MCP, and the Java API.

## Musical sections

List proposed phrases, periods and sections with
`reconstruct sections WORK_DIR`. Use `reconstruct sections add|set|remove|rename
WORK_DIR JSON` to maintain the separate `sections.json` journal. Select a
span by ID or unique name with `view --section NAME` or
`render-compare --section NAME`. Use `view --sections` to show the bands
with explicit time bounds. Roleplan rules accept a singular `section`
field and edit spans accept `{"section":"chorus-1"}`. See the
[musical sections guide](../docs/architecture/reconstruction-stages.md#musical-sections)
for payloads, split and merge, and MCP equivalents.

### Recover pitch approaches from mapped stems

On S3K, transcription follows the source pitch backward from a settled
note. A continuous rise can become an onset scoop or a single bent gate
across adjacent transcription fragments. A continuous pickup keeps its
initial pitch until the rise starts. The evidence plot marks the bend start
at the sounding pitch. A confirmed approach also guides
the search for matching rises in other written chord voices, which can
play on FM or PSG tone lanes. Use
`reconstruct proposals list WORK_DIR --group stem-pitch-approach` to inspect
weaker candidates. Rebuild with `reconstruct run --from transcribe WORK_DIR`
to scan an existing reconstruction. See
[Stem-backed pitch bends](../docs/architecture/reconstruction-stages.md#stem-backed-pitch-bends)
for curve and routing details.

### Recover missing notes from mapped stems

Staged `reconstruct run` and `reconstruct transcribe` measure excess
partials 2–4 on each mapped tonal stem and record them in
`stem-template-candidates.json`. For review, the detector focuses on the
octave and twelfth, up to C7 or the highest written pitch on the part.
It compares each peak to the same phase of nearby isolated notes with
similar duration and velocity and checks whether neighboring partials
also grew. An upper ridge needs evidence of separate pitch or timing to
be shortlisted. A tightly matched glide or vibrato suppresses the proposal.

The strongest shortlist entries become agent `insertNote` proposals in
the `audio-template` group, with up to three per 16 seconds of source
time across the song. The report includes the shortlist IDs and other
peaks. A strong, distinct attack with enough control notes can add
harmony with `audio-template` provenance. Use `--no-stem-template-notes`
on a fresh run (MCP `options.noStemTemplateNotes`) to suppress those
automatic insertions.

Use the proposal commands above to list, preview, accept or reject entries.
Acceptance takes effect on a rebuild from `transcribe`. Retranscription
refreshes the pending detector queue and keeps accepted edits and
rejection history.

## Reconstruction layering

New staged runs, including import and MCP, choose `s1-layering`,
`s2-layering`, or `s3k-layering` from the target game.
These presets add octave-up PSG doubling and a 14-tick FM echo on spare channels.
S1 and S2 use hold-terminated volume envelopes and reserve PSG3 for the
whole noise track when it is needed. On S2, set `OPENSMPSDECK_S2_ROM` to a
local ROM to consider close stock envelope matches. Check `psgEnvelopes`
in `report.json` for stock and custom counts, custom bytes, and approximations.
[PSG driver rules](../docs/architecture/driver-support.md#psg-reconstruction-on-s1-and-s2)
FM-only runs use the FM echo. The echo defaults to 11
carrier TL steps below the dry note (8.25 dB nominal), or 3 dB quieter than
the original 7-TL echo.
Set `lanePolicies.echoAttenuation` in `voicing.json` to override that level,
then assemble using the saved policy. Existing work directories and their
reruns keep the saved voicing policy. Use `--lane-policy-preset off` when
starting a run or import, or pass it to `reconstruct assemble WORK_DIR` to
clear an existing policy. Pass the target's preset name to restore layering.
For MCP `reconstruct_stage`, pass `stage: "assemble"` and
`options.lanePolicyPreset` to set or clear the preset. See the
[lane policy guide](../docs/architecture/reconstruction-stages.md#optional-lane-policies)
for the override and custom scopes.

For individual attacks, use the [`layer` edit](../docs/architecture/reconstruction-stages.md#persistent-musical-edits)
or Studio **Selection → Layer**. Choose echo, chorus, double or off, then
rebuild from `assemble`. The selected notes' layer intents override the
preset. Inspect `report.json.noteLayers` for applied and skipped copies.

### Staged vibrato

On an allocated S3K FM note, a `noteVoicing` edit can set
`vibratoEnvelope` to an ordered array such as
`[{"startOffset":18,"rateHz":5,"depthCents":20},{"startOffset":32,"rateHz":5,"depthCents":48}]`.
The opening is flat. These offsets are driver ticks from the note attack.
A zero-depth stage stops vibrato. Submit the edit with `reconstruct edit`
or MCP `reconstruct_edit` and rebuild from `assemble`. Use `trace` or
`describe` to check the assembled stages.

Run `reconstruct vibrato-review WORK_DIR [--part ID] [--propose]` or
`reconstruct_vibrato_review` over MCP to detect delayed or changing vibrato
in mapped stems. Omit `--propose` (MCP `propose`) for read-only detection.
Review or accept the proposals in Studio or with `reconstruct proposals`.
See the [envelope contract](../docs/architecture/reconstruction-stages.md#per-note-vibrato-envelopes).

## Reconstruction benchmark

Use `bench build-corpus` with a local locked-on ROM and catalogue, then
`bench run` to score reconstruction across MIDI profiles and audio variants.
`--quick` selects four songs, `--holdout` excludes the song's own (non-library)
assets from the catalogue, and `--gold` accepts private hand-labelled lead regions. Use
`bench compare` to gate changes against a saved scorecard. Use
`bench lead-gap WORK_DIR --stem LEAD.wav` to measure missed FM1 coverage
against an aligned modulated lead stem. Keep all inputs and
generated datasets outside the repository. See the
[benchmark guide](../docs/architecture/benchmark.md) for commands and metric
definitions.

Use `bench build-corpus ... --ladder --game s1|s2|s3k` to compare native
channel MIDI and stems, grouped inputs, mix effects, external transcription,
and the Suno-like profile with and without tempo drift. The ladder defaults
to 30 seconds. Pass `--seconds 120` for a longer window, `--full-length` to
stop at the native end or first completed loop, or `--torture` to run the
credits medleys through the exact and grouped rungs. Run the resulting
manifests with `bench run`, then use
`bench ladder-report OUT_DIRS... --out REPORT_DIR` for Markdown, JSON, and a
private listening index with embedded MP3s. Ladder listening export uses
`ffmpeg` with `libmp3lame`.


## Build nested arrangements

Use `group create` to turn existing placements in a shared project into
one reusable group. The first instance ID is `GROUP_ID-placement` (with a
numeric suffix if needed). `group place` adds a linked instance by ID at an
explicit tick and length. `group ungroup` replaces the named placement with
its immediate children, expanding full and partial repeats.

```sh
java -jar "$CLI" group create song.smps.json chorus "Chorus" lead-clip bass-clip drums-clip
java -jar "$CLI" group create song.smps.json rhythm "Rhythm bed" bass-clip drums-clip --in chorus
java -jar "$CLI" group place song.smps.json chorus chorus-second 384 144
java -jar "$CLI" validate song.smps.json
java -jar "$CLI" group ungroup song.smps.json chorus-second
```

Set `CLI` to the path of the packaged headless JAR. The example assumes the
first three placements exist in the song and tick 384 is available. Append
`--in GROUP_ID` to any group command to operate on a group's source timeline
instead of the song. Each successful command saves the file using the loaded
revision. A failed edit leaves the file intact. `validate` is an alias for
`validate-project` and reports invalid links, cycles, depth and lane conflicts
with a nonzero exit status.

Agents can also author the [version 6 JSON fields](../project-core/README.md#version-6-group-sequences)
directly. See [Signal garden](../examples/signal-garden.smps.json) for a nested
arrangement with linked instances and partial repeats.

## Package provenance

Package exports warn when they embed assets derived from the ROM or
copyrighted retail song data. Keep these packages for personal use and do
not redistribute them. Portable mode can reference eligible stock assets.
Warnings go to stderr for CLI package writers and appear in the `warnings` array for
MCP package exports and reconstruction runs. Studio shows them before writing.
Provenance warnings permit export.

`stock-table build ROM OUT.json` rebuilds the public stock hash and locator
table. The default uses locked-on S3&K. For Sonic 1 or Sonic 2, pass
`--driver s1` or `--driver s2` and use `--rom OTHER_REVISION.gen` to take
the union of both verified revisions. Choose a new output path.

## Portable song packages

Schema 4 stores an S1, S2 or S3K bank with relocations and references to known game
assets. Original payloads remain embedded. Stock matches are resolved
from the player's own ROM at playback. By default, retail-derived songs
remain embedded and carry a redistribution warning.

```sh
java -jar "$deck_jar" compile-package PROJECT.smps.json song.smps.zip --portable
java -jar "$deck_jar" export WORK_DIR song.smps.zip --format portable --driver s2 --no-source
java -jar "$deck_jar" inspect-package song.smps.zip
java -jar "$deck_jar" validate-package song.smps.zip --rom locked-on.gen --render verified.wav --frames 3600
java -jar "$deck_jar" export PROJECT.smps.json replacement.jar --format mod --driver s1 --replace 129
```

Portable exports include redacted source by default. Pass `--no-source`
to omit it. Choose `--driver s1|s2|s3k` for an editable arrangement.
The `export` command and Studio use the project target by default.
Native v5 banks support `s3k`. S1 and S2
preserve the S3K duration tick rate as closely as their native clocks allow.
`--speed-up-tempo BYTE` (decimal 0–255) uses the S3K clock for the optional
gameplay speed-shoes tempo, then converts it to a native manifest byte.
An absent value leaves gameplay tempo unchanged. Export reports
`TEMPO_APPROXIMATED` with the source and target bytes and the signed
relative tick-rate error for each inexact conversion. Pass `--strict-tempo`
to reject either approximation. The package ID
defaults to a lowercase slug of the project name,
the version to `1.0.0`, the author list to empty, and the license to null.
Use `--package-id`, `--package-version`, repeatable `--author`, and
`--license` with an SPDX expression to set these fields. A mod JAR requires
at least one decimal `--replace` stock music ID for the selected driver. Repeat the flag to map
several IDs to the exported song. Its required mod author defaults to
`Unknown` when no author was supplied.

For a native v5 project from `import-rom-song`, add `--retail-patch` to
`compile-package --portable` or `export --format portable|mod`. Supply
`--rom PATH` for an edited bank. An unchanged bank listed in the stock
table can export without the ROM. The patch stores the changes and always
omits the source. It warns that the composition still belongs to the game
(`DERIVED_FROM_RETAIL_SONG`). Compiled arrangements from `copy-arrangement`
require embedded export and retain `RETAIL_SONG_DATA`. OpenGGF's
`smpsPackage` reader still rejects patch form, including in mod JARs.

`inspect-package` returns the validated schema 4 manifest and warnings,
including a patch's bank form and base song.
`validate-package` returns a JSON report with `structureValidated` and
`playbackValidated`. Repeat `--rom` to supply several images. Without a ROM,
packages with references are checked structurally. For a patch bank,
`playbackPending` identifies the base song that needs a ROM. Pass the
matching ROM to rebuild the bank and verify its hash. `--render` writes a new
48 kHz WAV after resolution and native preflight. `--frames` overrides
the stored preview length. `render-package PKG OUT.wav [--rom PATH ...]
[--frames N]` runs the same native rendering path with a positional output.
When using an extracted redacted source, pass
`--rom PATH` to `validate-project`, `render-project`, `compile-package`
or `export` to resolve its stock assets. At export time, supplying a ROM
also verifies stock references in already-resolved projects and packages. Export checks the base payload and compares the transformed bytes
with the stored payload or manifest hash when present. A mismatch names the
asset slot and prevents invalid output. Unknown ROM fingerprints warn, and
matching asset hashes still permit export.

Studio offers portable, mod and disassembly exports with an optional local ROM.
See [Studio export](../docs/user-guide/studio.md#listen-and-export) for the picker
and warning review.

MCP `project_export` accepts `format: "portable"` or `"mod"` and optional
`driver`, `speedUpTempo`, `strictTempo`, `noSource`, `retailPatch`,
`license`, `packageId`, `packageVersion`, `authors`, and `roms`. Use an integer `replace` array for mod export.
`package_validate` takes `package`, optional `roms` and a new `render` path
beside the package with optional `frames`. `package_inspect` takes `package`.
See the [driver feature matrix](../docs/architecture/driver-support.md)
and the [normative package contract](../docs/architecture/portable-package.md).
