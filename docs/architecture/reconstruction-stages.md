# Staged reconstruction

Reconstruction builds an S3K arrangement from aligned MIDI and audio. Each
stage reads the preceding artifact from a work directory and writes its own
versioned JSON. Edit the artifact that owns the decision, then resume at the
first stage that consumes it.

```sh
jar=headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar
java -jar "$jar" reconstruct run song.mid mix.wav catalogue.json song-work \
  --seconds 60 --stem 0=lead.wav --stem 1=bass.wav
java -jar "$jar" reconstruct run --from allocate song-work
```

The first command runs all six stages in a new directory. Use
`--audio-repeats` to include reference chroma in phrase matching. The
transcription options are saved for subsequent runs. The second
rebuilds allocation, voicing and assembly from the existing `roles.json`. To run
individual stages, use the following commands.

```sh
java -jar "$jar" reconstruct transcribe song.mid mix.wav catalogue.json stage-work \
  --candidates candidate-notes.json
java -jar "$jar" reconstruct structure stage-work
java -jar "$jar" reconstruct roles stage-work --harmony-max-hz 7.5
java -jar "$jar" reconstruct allocate stage-work
java -jar "$jar" reconstruct voice stage-work
java -jar "$jar" reconstruct assemble stage-work
```

| Artifact edited | First downstream stage |
| --- | --- |
| `score.json` | `structure` |
| `structure.json` | `roles` |
| `roles.json` | `allocate` |
| `allocation.json` | `voice` |
| `voicing.json` | `assemble` (or `run --from voice`) |

Use `run --from transcribe WORK_DIR` to regenerate the score with the paths,
duration, and transcription options stored in the existing score. An explicit
stage writes only its own output. Resuming a chain replaces its downstream
artifacts, so retain copies of any downstream edits you want to keep. The
four assembly files are prepared before replacing the previous assembly.
Invalid artifacts return exit code 2 with the artifact and failing field or
decision. Input paths and artifacts may contain private material. Keep work
directories outside public checkouts.

## Artifact contract

Each artifact is an object with `"version": 1`. Fields in this guide are
required unless marked optional. Optional fields accept null or omission.
Unknown fields, invalid references, unsupported versions, and artifacts over
32 MiB are rejected. IDs are strings. Within each list of identified records,
IDs must be distinct and nonblank. Preserve an ID when editing its note.

The same note shape is used in every stage. Times are measured in seconds
from the start. Confidence is in 0–1, and velocity is in 1–127.
`score.json` always uses source seconds. `structure.json`, `roles.json` and
`allocation.json` label their note and bar times with `timeBase`, either
`source` or `project`. `roleplan.json` uses that same time base for
`startSeconds` (inclusive) and `endSeconds` (exclusive), as displayed by
`reconstruct describe`. A generated plan includes `timeBase`. A hand-written
plan can omit it to inherit the structure time base.
`part` refers to a MIDI part ID in `score.json`. Pitch is a MIDI number.
S3K melodic notes span 12–106. Percussion and external candidates use MIDI
0–127. The score can carry a coincident MIDI attack and release, which
occupies one presentation when placed.

```json
{
  "id": "n-0-1250000-72",
  "part": 0,
  "start": 1.25,
  "end": 1.5,
  "pitch": 72,
  "velocity": 96,
  "source": "midi",
  "confidence": 1.0
}
```

IDs generated from MIDI use the part, attack in microseconds, pitch, and an
occurrence suffix when needed. Downstream files retain the IDs. A consensus
replacement keeps the ID of the note it corrects. An addition takes its ID
from the target bar and the first voting note. Phrase additions also include
the phrase ID.

### Transcribe

`score.json` contains the following fields alongside `version`.

| Field | Contents |
| --- | --- |
| `durationUs`, `frames`, `ticksPerSecond` | Requested interval in microseconds, its ceiling in presentations, and the 60 Hz placement grid. |
| `inputs` | Absolute `midi`, `wav` and `catalogue` paths. Also stores `name`, `ticksPerBeat`, `keepOctaves`, `fmOnly`, `wholePartVoices`, `audioRefine`, `stems` (part ID to absolute WAV path), optional `candidates`, and `harmonyMaxHz`. |
| `bars` | Musical grid in elapsed seconds. Each bar has `id`, `start`, `end`, and `complete`. A pickup, meter change between nominal boundaries, or cut at the requested end can produce a partial bar. |
| `tempo` (optional) | Grid summary with BPM, beat phase, bar offset, beats per bar, and evidence. `source` is `tracked`, `estimated`, `override`, or `midi` when the PPQ grid is retained. |
| `beatMap` (optional) | Editable `beats` in source seconds, zero-based `downbeat` phase modulo `beatsPerBar`, and `confidence` from 0 to 1. The first or last anchor may extend past the requested interval. |
| `midiBars` (optional) | Original PPQ windows kept for role continuity when an automatic estimate supplies the musical grid. |
| `source` | Reference context for matching. `parts` has `id`, `name`, `program`, and `drums`. `notes` has `partId`, `pitch`, `velocity`, `startUs`, and `endUs`. `warnings` contains MIDI parser messages. |
| `sourceCounts` | Original note count per MIDI part inside the interval. |
| `notes` | Editable notes after clipping to the interval and the existing pitch-range and systematic octave-double cleanup. Sources are `midi`, `audio-refine`, or `external`. |
| `reductions`, `pitchChanges` | Original preparation decisions and optional audio-refinement evidence, also exported in the report. |

With no MIDI tempo event or one 120 BPM event, transcribe looks for gradual
tempo changes in overlapping onset windows. A shared linear BPM trend seeks
maximum agreement across the windows and penalizes steep changes. The search
covers up to about 18% total drift. Phase measurements correct the trend locally
when the rate of change varies. Drum onsets carry the most weight. When the
onsets independently reveal a frame clock across the entire interval, that
clock refines an already detected ramp to sub-frame precision. Kick, snare
and cymbal accents help choose the beat and downbeat. Melody, bass and the
mix onset envelope support those decisions. An explicit quarter-note MIDI
meter takes precedence, with 4/4 as the default for a tracked grid.

The existing constant-grid estimate covers pieces with a steady pulse and
provides a fallback when the tracker lacks a coherent path. It uses periodicity
at beat, eighth-note, sixteenth-note, and triplet intervals.
Coincident MIDI attacks count once. Reference RMS onset peaks add evidence
when the same tempo explains both signals. MIDI percussion accents and
low-note changes distinguish beats and downbeats. An opening shared by
multiple parts adds downbeat evidence. The estimator prefers 4/4 over
3/4 unless the latter has a clearer accent pattern. An explicit quarter-note
meter at MIDI tick zero takes precedence. A weak or ambiguous estimate
retains the PPQ grid. `--estimate-tempo` searches even when the MIDI provides
a tempo map and uses the best estimate. `--tempo BPM --bar-offset S` sets
a constant grid manually. The offset is the downbeat in seconds, wrapped
into the first bar, and defaults to zero. These options leave note attack
and release times intact. Use a valid MIDI tempo map for deliberate abrupt
tempo changes.

Variable-length bars follow the selected beat map. A confident curve that
deviates at least 0.6% from the opening rate can supply structure bars even
when playback stays in source time. The tempo summary uses `tracked` in
that case. To correct a tracked grid, edit `score.json` at `beatMap.beats` or
`beatMap.downbeat` and run `reconstruct run --from structure WORK_DIR`.
The structure stage rebuilds its bars from the edited curve. Corrections to
a beat map can also change project timing under the saved steady-tempo
setting.

Use `--steady-tempo off|auto|BPM` on transcribe or a fresh run. The default
`auto` uses the first stable opening tempo when a confident curve shows at least
0.85% sustained deviation. For a ramp that starts immediately it uses the
initial rate of the smoothed beat path. The sustained measurement averages
over beat windows, so a 1.2% linear rise can register below 1.0%. `off` keeps
source time. A number sets the project BPM. With `--tempo BPM`, auto and off keep
the manual constant source grid and source playback times. A numeric steady
tempo with an explicit source tempo scales the entire timeline by their
ratio. The setting is saved at `inputs.grid.steady`.

When warping applies, structure maps the score's notes, candidates and bar
boundaries into project seconds using
`(beatIndex(source) - beatIndex(0)) * 60 / steadyBpm`. The origin stays at
zero, including for pieces with pickups. Structure, roles and allocation
then share the project clock. Allocation rounds to the nearest 60 Hz
presentation, clamps releases, and gives each note at least one tick.
Stable IDs tie downstream decisions to the source score. Structure maps audio
comparison windows back to source seconds, and the voice stage maps the
allocated notes back before fitting the original mix and stems. The inverse
also works for notes created or retimed downstream. `score.json` stores the
source duration and frame count.

Earlier source-time stage files without a label still work in unwarped runs.
For old warped work or after changing the tempo map, rebuild starting from
structure. Convert any old role plan time bounds with `time-map` and set its
`timeBase` to the value shown by `describe` before resuming. Use either
direction of the mapping from the CLI.

```sh
java -jar "$jar" reconstruct time-map WORK_DIR --source 42.5
java -jar "$jar" reconstruct time-map WORK_DIR --project 43.1
```

The preparation audit fields have the following shapes.

- `reductions` contains `{note, change}`. Here `note` uses the microsecond
  shape from `source.notes`. Preparation codes are `OCTAVE_DOUBLE`,
  `PITCH_OUT_OF_RANGE`, and `CUT_AT_END`.
- `pitchChanges` contains `{partId, originalPitch, pitch, startUs, endUs,
  reason, source, stem, supportingFrames, meanHarmonicEnergy}`. The
  `stem` flag identifies audio evidence from a supplied stem.

`--candidates` accepts a separate JSON file in the following format. The
part must exist in the MIDI and be melodic. The optional analysis tool runs
outside this pipeline. Candidate IDs are prefixed with `external-` in
`score.json`. Candidates have confidence 0.35 and can corroborate a
consensus suggestion for the same part, onset and pitch.

```json
{
  "version": 1,
  "notes": [
    {"id": "candidate-1", "part": 0, "start": 5.25, "end": 5.44, "pitch": 72, "velocity": 90}
  ]
}
```

### Structure

`structure.json` includes the bar grid under `bars`, contiguous
`sections` with `{id, bars}`, and `repeats` with `{id, bars}`.
Both `bars` arrays within identified entries contain bar IDs. Sections
cover the grid in order. Complete bars can belong to one repeat group each.

The `phrases` array records tolerant repeat groups over complete 1-, 2-, and
4-bar windows. Each group has an ID, `instances` (each with an ordered
`bars` array), and minimum pairwise MIDI and optional audio evidence. The
same melody extractor used by roles runs provisionally inside structure.
Roles runs once more on the corrected notes. For automatic estimates,
`roleLayout` preserves the original contour windows, their sections, and
their high-agreement bar matches. This keeps instrument-role boundaries
stable as the musical grid changes. When auto-tracking a placeholder MIDI
tempo and warping the score, the original PPQ contour windows are laid out
at their fixed spacing in project time. Edits to the corrected notes and the
saved role layout are used when resuming at roles. Manual grids and older
artifacts without a role layout use their main bars and sections directly.

Phrase matching scores ordered note alignments within each octave-folded
pitch class. It rewards matches within 0.70 of a sixteenth note and
penalizes onset error and unmatched attacks. The melody can match across
parts, and each part is checked independently so a change in accompaniment
can coexist with a repeat. Every pair within a group must pass. With
`--audio-repeats`, per-beat chroma cosine similarity can confirm a weaker
MIDI match or reject a strong MIDI match whose harmonic content disagrees.
Roles use only the legacy close bar matches for melody-label transfer.

`corrections` lists proposals with `id`, target `barId`, proposed
`note` with source `consensus`, optional `replaces` note ID, `votes`
with `{barId, noteId}` by independent supporting instance, optional
`externalCandidate`, and `applied`. A phrase proposal also names its
`phraseId`. Votes align the same part and exact pitch, and a nearby
octave-equivalent attack already fills the slot. Application requires two
other instances, votes from at least two thirds of the group, onset
agreement, and confidence of at least 0.78. Phrase additions additionally
require a one- or two-attack shortfall in the target part or a corroborating
external candidate. For older-style bar corrections, bar references use
`roleLayout` when present. All phrase references use the musical grid.

On an estimated or overridden grid, `repeats` lists exact bar-content
candidates after correction. Tolerant phrase groups remain evidence for
editing. Assembly independently compares actual lane content before
sharing a clip source, including relative presentation timing,
articulation, and selected instruments. With the original PPQ grid,
legacy bar matches continue to seed that final content check.

`notes` is the corrected score consumed by roles. It contains the
accepted edits with source `consensus` and the surviving score notes.
To accept or reject a proposal by hand, edit this list and update its
audit entry if needed. `corrections` is the decision history. External
candidates stay in `score.json` as evidence.

### Roles

`roles.json` contains `harmonyMaxHz` and a `notes` array with
`{note, role, reason?, mergedInto?}` per corrected note. Valid roles
are `melody`, `counter`, `bass`, `harmony`, `drums`, and `drop`.
A drop requires a reason.

Melody follows high, sustained or accented lines while retaining continuity
with the preceding line in a section. Monophonic MIDI parts are kept intact
as candidates. Polyphonic parts are split by nearby attack times, compatible
releases, pitch intervals and durations. Melody labels transfer between
matched notes in repeat instances. The lower and secondary contours supply
bass and counter material.

A gap in the selected line can receive the tail of an already sounding
note in the same MIDI part. The chord keeps its original attack. The tail
follows the lead's release and is scored using the line tracer's leap and
gap costs. Explicit role plans can take the same handoff within their scope.
Without a part stem, a handoff requires close, unambiguous MIDI motion into
and out of the tail. Split tails carry the reason `lead-continuation`.

A supplied `--stem PART=WAV` enables lead evidence for that part at the
roles stage, independently of `--audio-refine`. Analysis reads the original
stem in source time, including when the arrangement uses the steady
project clock. A short Fourier window tracks harmonic peaks. A detrended
4–8 Hz fit estimates periodic frequency motion, favoring partials that
simultaneous MIDI notes do not share. Strong periodic amplitude motion
rejects likely beating. A separate trend score estimates short glides.
Notes with measured evidence carry optional `motion: {vibrato, glide}`
with scores from 0 to 1. This evidence gives melody selection a bounded
bonus.

The roles stage can add a nearby, modulated tone that stays present for
at least 150 ms and has no corresponding MIDI note. It records
`source: "audio"` and `lead-audio-added`. A melody release can also extend
up to the next selected attack when the stem continues the pitch with
adequate level and sustained pitch motion. Its reason includes
`lead-audio-extended`. These edits leave `score.json` and
`structure.json` intact. `reconstruct describe` marks notes with strong
periodic pitch evidence with `~` and summarizes the edit counts.
`report.json` records the same counts under `stages.leadContinuity`.

After those repairs, a counter segment can fill a gap between melody notes
from the same MIDI part. The whole segment must fit inside the gap. Its
notes must sound for at least 150 ms in total and connect to each other
and both melody notes under the line tracer's transition cost. With
MIDI alone, the gap is limited to one second and the two handoffs may
total at most 120 ms of silence. Measured stem pitch motion can support
longer phrases. Explicit `roleplan#N` assignments are preserved. Promotions
carry the reason `melody-gapfill`.
`report.json` records `stages.melodyGapFill.beforeSeconds` and
`afterSeconds`. These fields measure time with no melody sounding while
a counter note plays in any part that carries melody, using the roles
timeline before legato and hardware allocation.

After promotion, stem-backed legato can close a gap of up to 350 ms in
source time between consecutive melody attacks from the same MIDI part.
It requires the earlier or following pitch's fundamental throughout the
gap at a substantial fraction of its level inside the corresponding
note. The evidence can pass from the earlier pitch to the following
pitch once. It excludes evidence
that could be supplied by an overlapping MIDI tone or its harmonics.
For gaps shorter than the spectrum window, it also checks samples
strictly inside the gap so a same-pitch reattack cannot supply all the
evidence. Motion-based releases retain priority. Legato only changes the
earlier note's end and records `lead-legato` in the roles and continuity
counts. With stems, `stages.fm1InterNoteGaps` reports the counts of each
FM1 gap length in 60 Hz project frames, including zero-length gaps,
before and after legato on the allocated lane.

Rapid re-attacks on close, similarly accented notes of the same inner
pitch can merge into the earlier note. The earlier note extends its
release and records `sustained-inner-tone`. The dropped attack records
`rapid-inner-tone-reattack` and the retained ID in `mergedInto`.
Isolated same-velocity, same-onset octave doubles remaining in harmony
can be dropped with reason `isolated-harmony-octave-double`. A continuing
octave pattern is kept. The default maximum harmony re-attack rate is
7.5 per second. Use `--harmony-max-hz 0` at the roles stage to disable
both harmony reductions for comparison. A roles-only override is saved
in that artifact and takes effect until roles is run again.

### Allocate

`allocation.json` contains a `notes` array with
`{note, role, outcome, lane?, startTick?, endTick?, reason?, mergedInto?}`.
`outcome` is `kept`, `shortened`, or `dropped`. Shortenings and drops
require a reason. Kept and shortened entries have a lane and a nonoverlapping,
half-open presentation interval. The interval in the placement is
authoritative for assembly. Drops carry neither lane nor interval.

| Role | Hardware |
| --- | --- |
| Melody | FM1 for the whole song |
| Bass | FM2 for the whole song |
| Counter and harmony | FM3–FM5 first, then PSG1–PSG3 for eligible pitches |
| Drums | DAC, with explicitly labelled `PSG3` or `PSG noise` percussion parts on PSG3 when enabled |

The shared project format exposes five FM tone lanes, three PSG lanes
and DAC. The sixth FM channel and DAC share hardware. The arrangement
compiler exposes that slot as DAC. `--fm-only` disables PSG placement
while keeping DAC. Release collisions, unavailable lanes and simultaneous
DAC hits appear in the allocation and the final report.

### Voice

`voicing.json` maps hardware lane IDs to settings under `lanes`. FM lanes
have one `variantId` for the allocated notes. The `shortlist` lists up to
18 candidate IDs, their pitch-aligned attack match scores (higher is better),
and their contextual fit and held-out errors (lower is better). DAC and PSG
retain part-level matching under `instruments`. DAC entries also specify
`drumPitch`. The PSG3 noise allocation supplies its native noise command.

The fit renders the allocated phrases through the pinned S3K chips over up to
eight excerpts spread across the song. It models the reference magnitude
spectrum as a sum of lane spectra and uses constrained least squares to
estimate the lane gains. The mix models all lanes. Where stems are supplied,
the stem objective also compares the lane's notes from its most represented
stem against that reference. Separate excerpts validate patch changes.
Articulation and effect alternatives must also win on both partitions to
replace the role default. Insufficient evidence records
`default (inconclusive)`. Melody defaults to mild delayed vibrato and adds a
quiet echo when an FM lane is free. Harmony defaults to light detune. Bass
has a shorter gate and no default pitch effect.

Edit `variantId` using a catalogue variant ID. `gain` is a linear amplitude
multiplier converted to native volume (0–4, subject to chip headroom).
`articulation.gate` is the fraction of the allocated duration held before
key-off. FM `minimumReleaseRate` floors the four operator RR nibbles (0
preserves the patch). `effects.detune` is a signed frequency offset.
Optional `effects.vibrato` holds the four native F0 parameters `delay`,
`rate`, `delta` and `steps`. Optional `effects.copy` gives an unused FM
`lane`, `delay` in ticks, relative `gain`, signed `detune`, and optional
`vibrato`. Preset names and decision reasons are descriptive. The numeric
settings control assembly.

Run `reconstruct run --from assemble WORK_DIR` after a hand edit.
`run --from voice` also reuses an existing voicing, or generates one if the
file is missing. Use `reconstruct voice WORK_DIR` to refit after editing
allocation. A refit starts from each still-available FM variant in the
existing artifact. A challenger must improve both fit and held-out error
by more than 0.5% to replace the previous variant. A measured near-tie
changed the fit by 0.18% while switching to a markedly different gain.
The margin puts that change below the replacement threshold. Gain,
articulation and effects are fitted again.

The optional `fitWindows` object stores the source length in 60 Hz frames
and excerpt start ticks in time order. Refits reuse those excerpts when
the source length is unchanged, and resolve equally scored challengers
in variant ID order. Older files without excerpt data select and save
excerpts on the first refit. To force a fresh fit, move `voicing.json` to a
backup name, then run `reconstruct voice WORK_DIR` or the MCP tool
`reconstruct_stage` with `stage: "voice"`. To refit and reassemble in one
command after moving the file, use `reconstruct run --from voice WORK_DIR`.

### Assemble

Assembly reads the selected voices, gates, gains and native effect settings
from `voicing.json`. The output is `arrangement.smps.json`, `arrangement.smps.zip`,
`render.wav`, and `report.json`. The report includes stage totals,
matcher evidence with lane IDs, and reductions with note IDs and reasons.
`tempoCurve` includes the start and end BPM when known, the maximum sustained
fractional deviation from the opening tempo, confidence, whether a warp
was applied, the steady BPM when applicable, and the presentation-rounded
duration in project seconds. `stages.phraseGroups` counts tolerant repeated
phrases and `stages.repeatGroups` counts exact bar candidates for clip reuse.

Warped runs also write `reference-steady.wav` and attach it at offset zero
in the Studio arrangement. The stereo stretch uses cross-correlated
overlap-add to preserve pitch and applies the inverse project-to-source map.
With `render_compare`, provide the work directory, its `render.wav`, and the
original mix or the steady reference. MCP substitutes the steady file when
the original mix matches the assembly report. A/B excerpt ranges use project
seconds. Source mix and stem excerpts for voice fitting always use source
seconds.
`effectCopyLanes` identifies dedicated FM copy lanes that actually reached
assembly. If copies would make the bank exceed the native size limit,
assembly retries without them and records `echoBankLimit`.
Repeat clips for a lane share one sequence ID when the relative note timing,
pitch, articulation and instrument commands agree. Releases crossing a
bar edge keep their continuity. The package compiler bakes source reuse
into the playback bank.

## Benchmark

`bench run` invokes the staged chain in process. The benchmark excludes
report-flagged effect copies from note events and measures the complete audio
render. The `suno-like` profile adds musical-grid and repeat measurements.
The runner also retains
`stage-reductions.json` with reason counts per case. Use the
[benchmark guide](benchmark.md) for the corpus, holdout and gold commands.
The legacy positional `reconstruct MIDI WAV INDEX OUTPUT_DIR` command
continues to use the original allocation policy for comparisons.
