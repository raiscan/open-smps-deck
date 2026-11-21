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

For an excerpt, use `render-project PROJECT.json OUT.wav --start S --end S`.
Bounds use project seconds rounded to 60 Hz. Playback warms up from zero
and discards audio before the start. Omit the start to begin at zero.
Omit the end to use `--frames` if supplied, or the project's presentation
count for a bounded render. `--solo FM1,PSG1` mutes all other lanes and
`--mute DAC,PSG3` mutes the listed lanes even when soloed.

For aligned A/B listening, use `render-compare WORK_DIR --start S --end S OUT.wav`
with source seconds. Use `--time project` for project seconds. It maps the bounds to project time and appends the
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

## Reconstruct a track from MIDI and WAV

Export MIDI and a WAV from the same time origin. Build a local [instrument
catalogue](../docs/architecture/instrument-catalogue.md) before you run:

```sh
deck_jar=headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar
java -jar "$deck_jar" reconstruct run song.mid song.wav instruments.json first-minute \
  --seconds 60 --name "Song"
java -jar "$deck_jar" reconstruct run song.mid song.wav instruments.json full-song --name "Song"
```

The command creates a new work directory with five editable stage artifacts
and four assembly outputs. Use `reconstruct run --from STAGE WORK_DIR` to
regenerate downstream decisions after an edit. Allocation prefers the prior
lane where it can do so with the same hardware priority and without
shortening another note. The
[reconstruction stage guide](../docs/architecture/reconstruction-stages.md)
documents the files, individual stage commands, and how to add external
note candidates. Use `reconstruct voice WORK_DIR` to fit `voicing.json`.
Edit that file to change lane patches, gains, note gates, or native effects,
then use `reconstruct run --from assemble WORK_DIR` to render the changes.

- `arrangement.smps.json` contains editable clips and local assets.
- `arrangement.smps.zip` contains the compiled S3&K bank and its resource tables.
- `render.wav` contains the 48 kHz stereo output from the pinned OpenGGF chips.
- `report.json` records input and output hashes, source part IDs, instrument
  shortlists, effect-copy lanes, and allocation drops and shortenings. Ten-second
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

The arrangement quantizes timing to 60 Hz. FM1 carries melody and FM2 carries
bass. Counter and harmony use the three remaining FM channels and can spill
to PSG within its pitch range. Rapid inner chord re-attacks can be merged.
`--harmony-max-hz 0` preserves those attacks for comparison. `--fm-only`
disables PSG. DAC carries drums. A percussion part named `PSG3` or
`PSG noise` can use PSG3 when PSG is enabled. The converter
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
within the phrase. With multiple variants the command writes `phrase-1.wav`,
`phrase-2.wav`, and so on. The table shows the variant ID, filename and
successive RMS bins in dBFS before optional reference level matching.
`--rms-json FILE` saves the paths, IDs and linear RMS bins to a new JSON
file. Warped projects can take the original mix or the saved steady reference.
In MCP, `reconstruct_audition` takes a `variants` array and optional `rmsJson`
path. It also returns these measurements inline and accepts `note` and `time`.

To apply a candidate, add an entry in the target lane's `overrides` array in
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
numbers. Use `sections`, `bars`, or both to match their intersection.
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

### Inspect time-aligned evidence

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
- `--ridges` writes `OUT.ridges.json` alongside `OUT.png` with the strongest
  harmonic-sum ridge per analyzed source column. Rows give time in the
  selected clock, fractional MIDI pitch and spectral strength in dBFS.
- `--db` shows each panel's peak spectral dBFS. `--shared-db` also maps
  both panels to the same 30 dB window, using the higher peak as the ceiling.
  `--pitch-preset drums` uses MIDI 35–81. Explicit pitch limits take precedence.
- `--lanes FM1,PSG1 --parts 0,3` filters the overlays and lane rows. The
  source spectrum still shows the selected audio. With `--render`, `--lanes`
  also solos those lanes in the chip panel, so it compares against a stem
  rather than the full mix. `--width PX` defaults to 1440
  (640–2400). A view can span up to 60 seconds in either clock. Use a short
  span to resolve closely spaced attacks.

MCP uses corresponding fields `time`, `stem`, `wav`, `render`, `pitchMin`,
`pitchMax`, `lanes` (array), `parts` (array), `harmonics`, `harmonicSum`,
`width`, `notes`, `harmonicsAt` (array), `ridges`, `db`, `sharedDb`,
and `pitchPreset`.

### Persistent musical edits

```sh
java -jar "$jar" reconstruct edit "$work" add   '{"id":"answer","reason":"second attack audible in stem","op":"insertNote","part":0,"start":12.5,"end":12.8,"pitch":68,"lane":"FM1","role":"melody"}'
java -jar "$jar" reconstruct run --from transcribe "$work"
java -jar "$jar" reconstruct edit "$work" add --file batch.json
java -jar "$jar" reconstruct edit "$work" list
java -jar "$jar" reconstruct edit "$work" remove answer
```

A batch is an ordered array of intents or a versioned edits object. The CLI
returns the first affected stage as `from`. MCP `reconstruct_edit` accepts
`workDir`, `action: "add" | "list" | "remove"`, and an `edit` object or
`edits` array for add, or an `id` for remove. Set `rerun: true` on MCP add
or remove to rebuild once and get a `traceDiff`. Check `lastRun` for invalid
or orphaned targets and blocked pins. Use `add --dry-run JSON` or MCP
`dryRun: true` to see matched IDs, incoming and resulting roles, and whether
each transition is already satisfied. Edit times default to source seconds;
set `"timeBase":"project"` on an intent for project bounds. An upstream rebuild reapplies the
intents, and manifest replay copies them into the new work directory.

See the [operation fields](../docs/architecture/reconstruction-stages.md#persistent-musical-edits)
for selectors, role swaps, velocity spans, extension guards and other edits.

### Per-note trace and replay

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
The trace shows each stage's version of the note, including handwritten
score edits awaiting a rebuild, and the reason and competing note IDs.
The diff compares stable IDs and sets `outsideSpan` when neither version
of the note touches the specified source-time interval. It groups contiguous
lane moves outside that span in `lanePermutations`, with the first diverging
IDs and suggested `pinLane` edits. `voicingChanges` lists lane variant,
gain and scoped override changes. `compiledTimeline` counts changed events
outside the span, with the first project tick and affected lanes.
`renderedPcm` counts differing saved PCM frames, including effects carried
over from earlier audio.

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
and options with the saved external notes, musical edits and role plan.
The manifest records the plan hash. Replay also reuses the saved clip
anchors and allocation affinity seed. Use `--keep-voicing` for accepted
voicing settings. Keep the recipe and snapshots with the private work
directory. Both `run` and `rerun` summarize invalid and orphaned edits
on stderr.

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

## External transcription

Suno MIDI is the default. To try an external transcriber on selected melodic
parts, supply an aligned WAV stem and a role for each part ID.

```sh
java -jar "$CLI" reconstruct run song.mid mix.wav catalogue.json external-work \
  --stem 0=lead.wav --transcriber muscriptor --transcribe-part 0=lead
```

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
and the original tempo analysis continue to use Suno MIDI. A missing or
failed command aborts the requested run.

For MCP, call `transcribers_list` and pass
`options: {transcriber: "muscriptor", transcribeParts: {"0": "lead"}}` to
`reconstruct_run` or a fresh `reconstruct_stage` for `transcribe`. Include
the stem in `inputs.stems`. The server uses its own local configuration.
The score embeds the imported notes and provenance, including file hashes,
and the report includes per-part provenance. `run --from transcribe`
reapplies the saved notes without invoking the external command.

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
