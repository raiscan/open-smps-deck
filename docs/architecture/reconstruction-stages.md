# Staged reconstruction

Start from a local mix with
[`reconstruct separate` and `reconstruct import --run`](../../headless/README.md#external-stem-separation).
This builds an S3K arrangement from the mix and aligned labelled stems
in audio-only mode. Aligned MIDI and audio are also supported. Each
stage reads the preceding artifact from a work directory and writes its own
versioned JSON. Record musical intent in `edits.json`, then resume at the
first stage that consumes it. Keep `score.json` and `allocation.json` as
generated evidence.

```sh
jar=headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar
java -jar "$jar" reconstruct run song.mid mix.wav catalogue.json song-work \
  --seconds 60 --stem 0=lead.wav --stem 1=bass.wav
java -jar "$jar" reconstruct run --from allocate song-work
```

The first command shows the direct MIDI route and runs all seven stages
in a new directory. For a build from import, use `IMPORT_WORK/reconstruction`
as the stage work directory. Use
`--audio-repeats` to include reference chroma in phrase matching. The
transcription options are saved for subsequent runs. The second
rebuilds allocation, voicing and assembly using the voices identified from `roles.json`. To run
individual stages, use the following commands.

```sh
java -jar "$jar" reconstruct transcribe song.mid mix.wav catalogue.json stage-work \
  --candidates candidate-notes.json
java -jar "$jar" reconstruct structure stage-work
java -jar "$jar" reconstruct roles stage-work --harmony-max-hz 7.5
java -jar "$jar" reconstruct voices stage-work
java -jar "$jar" reconstruct allocate stage-work
java -jar "$jar" reconstruct voice stage-work
java -jar "$jar" reconstruct assemble stage-work
```

| Authored input | First downstream stage |
| --- | --- |
| `edits.json` | Use the stage returned by the edit command |
| `roleplan.json` | `roles` |
| `voice-edits.json` | `voices` |
| `sections.json` | Resume at the first stage using the section selector or voice grouping |
| `voicing.json` configuration | `assemble` |

Use `run --from transcribe WORK_DIR` to regenerate the score with the paths,
duration, and transcription options stored in the existing score. An explicit
stage writes only its own output. Resuming a chain regenerates downstream
artifacts. The four assembly files are prepared before replacing the
previous assembly.
Invalid artifacts return exit code 2 with the artifact and failing field or
decision. Input paths and artifacts may contain private material. Keep work
directories outside public checkouts.

## Audio-only source score

Import recognizes a separator's `separation.json` and prepares an audio-only
request when the folder has no MIDI. For other aligned stem folders, pass
`--audio-only` explicitly. `run --audio-only` and `transcribe --audio-only`
also accept explicit stem paths. All three entry points create the source on
the input audio clock. The configured drum detector (spectral by default)
supplies a GM drum part. When there is no aligned drum stem, a configured
BS-RoFormer adapter supplies one from the mix. The initial work directory
snapshots this WAV and records the separator provenance for replay. Without
the adapter, drum detection uses the mix. Tonal adapters are selected by role.
With RMVPE configured for vocals, the alternate monophonic candidate and
the other candidates are measured against stem periodicity.
The beat tracker uses drum attacks when available and mix onsets otherwise.
Audio-only candidate ranking adds locally recurrent kick and snare patterns
and a soft 70–180 BPM preference to the onset fit. Competing tempo hypotheses
are stored with the selected grid. The downstream stages use the same score
interface as a MIDI run. If the resulting bank overflows, audio-only assembly
can thin automatic backing attacks and records them as `audio-only-bank-fit`
reductions.

`score.json` and `manifest.json` retain audio-only input roles, the detector
and its source-time hits. Each tonal part records its selected transcriber. The manifest
records hashes for the mix, catalogue and participating stems, and snapshots
each transcriber's output. Replay needs these audio and catalogue files and
uses the saved detector output and transcription snapshots. A paired vocal
transcriber snapshot retains both candidates. Replay re-evaluates each
candidate and their union against the aligned stem, then routes the
selected monophonic contour to the lead. For a selected syllable snapshot,
a pitch path scores each gate against RMVPE, independent period estimates
and the stem's fundamentals and partials. Large leaps and octave changes
cost more when local evidence is weak. Corroborated pitches in the
surrounding section, song and rhythmically matching phrases act as soft
priors. Strong local evidence retains the original pitch. Same-pitch
fragments join within a syllable and short unsupported attacks are
removed before register shift and allocation. The raw RMVPE snapshot
remains available alongside the automatic decisions in `vocal-review`.
The vocal patch probe ranks sustained FM timbres independently of the
singer's timbre.

In polyphonic stem recovery, a generated upper note is excluded when its
frequency, relative phase and amplitude envelope follow a concurrent
decoded lower note's second, third or fourth harmonic. The score reduction
and note trace report `stem-upper-harmonic`. Raw decoder notes are retained.

## Recover missed notes from stems

The transcribe stage records sustained excess at partials 2–4 using a
same-pitch or nearby-register median and spread from isolated MIDI notes.
Written notes and competing harmonics veto the initial peak. For an
octave or twelfth within the part's range (with a default ceiling of C7),
the detector compares equivalent note phases in up to eight controls,
preferring nearby exact-pitch notes and similar durations and velocities.
Excess on adjacent upper partials discounts common brightness changes.

Review candidates need separate pitch or envelope evidence, such as
a peak that persists beyond the fundamental. Candidates that follow the
fundamental's pitch motion stay in the report. Close fragments of the
same ridge share one review slot. At most three ranked peaks per
16-second source-time window enter `edits.json` as agent `insertNote`
proposals. `stem-template-candidates.json` contains the full scan, support
measurements, and `shortlistedProposals` IDs. Previously rejected IDs
stay suppressed on replay.

Only a strong, sustained independent attack with sufficient controls can
be inserted automatically as `audio-template` harmony.
`--no-stem-template-notes` on the initial run disables automatic insertion
and leaves detection and review enabled. The option carries into replay.
Use `reconstruct proposals list WORK_DIR --group audio-template` to inspect
the review queue.

The automatic roles pass can restore a short skipped pulse bracketed by the
same role in a regular monophonic run. The trace records
`regular-monophonic-line`. Explicit role rules still take precedence.

## Processing Suno downloads

`reconstruct import FOLDER WORK_DIR` checks the download before transcription
and atomically writes `WORK_DIR/import.json`. The CLI, Studio and
`reconstruct_import` MCP tool share the same report. The engine identifies
root mix/MIDI files and root or `stems/` WAV files, reads `stems.json`
(including Suno's `take1` mapping), and skips links and unrelated subdirectories.
Only file names and manifest values are used for detection. Downloaded files
are parsed as data.

The report records correlations for MIDI attacks against spectral flux and
MIDI note gates against pitch-band energy. Manual assignments reserve their
parts first. Automatic matching maximizes the sum of eligible pair scores
across the remaining stems and parts. The audio score gets a 0.05 bonus for
a name match or 0.03 for a General MIDI program-family match. These small
margins let labels resolve close correlations while allowing a stronger
audio match to win. Exact ties use stable stem-path and part-ID ordering.
The MIDI drum channel gives dedicated drum stems precedence over percussion
stems. The report shows the selected pair, the best other eligible stem for
the part, and the stem's best alternative part when available.

Level fitting uses one reference stem per part. When two stems have audio
scores of at least 0.30 with pitch support for the same part, and that part
leads one stem's other melodic candidates by at least 0.08, that stem gets an
`also_correlates_with_part` warning if another stem was chosen for the part.
Keeping one reference avoids raising the fit target by summing recordings
that may contain overlapping audio. Use the warning's `--assign` hint to
choose the other stem after listening.

The engine checks whole-file levels and durations, reconciles supported
mono/stereo sample rates on a common analysis
grid, and cross-correlates the mix waveform with the summed stems within two
seconds. Weak correlation produces a warning. A confident nonzero lag of
25 ms or more blocks reconstruction. Excluded stems are left out of audio
validation, alignment and mapping. File hashes still appear in the report.

Use repeatable `--assign FILE=PART` and `--exclude FILE` to re-check with
overrides. MCP accepts `assign` as a file-to-part object and `exclude` as a
list of files. Pass a trusted `--catalogue INDEX.json` (`catalogue` in MCP)
to complete the run recipe. With `--run`, the CLI starts the recipe
after checking the input hashes. On MCP, pass the returned `runRequest` to
`reconstruct_run` once its inputs include the catalogue. Results land in
`WORK_DIR/reconstruction`.

## Musical sections

`reconstruct sections WORK_DIR` proposes a source-time hierarchy on demand.
It combines MIDI and mix chroma, aligned repeat matches, lead releases and
rests, harmonic arrivals, and texture changes from note activity and mapped
stem levels. The bar grid constrains the cuts, with strong evidence allowing
half-bar cuts. Phrases usually span two to four bars. Periods group two or
more phrases. Similar passages reuse a letter, with a prime for a variant.
Boundary confidence reflects evidence strength. It is a heuristic score.

The result has `automatic` and effective `sections` arrays. Each span has a
level, ID, label, author, reason, time in both clocks, one-based fractional
bar positions, start/end confidence, and a containing parent ID when
available. All ranges include the start and exclude the end. Use IDs or
unique names as selectors. Reused labels can be ambiguous.

```sh
java -jar "$jar" reconstruct sections song-work
java -jar "$jar" reconstruct sections add song-work \
  '{"id":"chorus-1","level":"section","name":"Chorus 1","sourceStart":18,"sourceEnd":34,"author":"human","reason":"Lead hook enters"}'
java -jar "$jar" reconstruct sections rename song-work \
  '{"id":"chorus-1","name":"Refrain","author":"agent","reason":"Match score labels"}'
java -jar "$jar" reconstruct view song-work --section chorus-1 excerpt.png
java -jar "$jar" render-compare song-work --section chorus-1 comparison.wav
```

`add` requires a new ID. `set` accepts the same fields and replaces an
existing ID's span or creates one. To split, send a `set` array containing
the shortened span and the new span. To merge, extend one span in a `set`
array and include `{"id":"OTHER_ID","removed":true,"author":"human","reason":"Merged"}`.
`remove` takes `id`, `author`, and `reason` and records a deletion so the
proposal cannot immediately restore the removed span. All four commands
accept a JSON object or an atomic array. Authors are `human` or `agent`.
Authored spans at the same level cannot overlap. Intersecting authored
parent and child spans require full containment.

`sections.json` stores only authored entries and deletions, with source
seconds and fractional bar anchors. Reruns retain source times and report
anchors that move by more than a quarter bar or fall outside the new
duration. Out-of-range entries remain in the journal for repair.
The proposal is an annotation over the existing stages. A stage uses it
only when an explicit roleplan or edit selector requests it.

- Roleplan rules accept `"section":"chorus-1"` for a section or period and
  intersect it with any other scope. Existing `sections` arrays still refer
  to the structure stage IDs.
- Span edits accept `"span":{"section":"chorus-1"}` wherever a numeric
  span is accepted. Resolve the selector on each run of the consuming
  stage. After moving a designation, resume from the edit’s first stage. Chord
  voicing edits start at `allocate`.
- View and render comparison accept `--section NAME` in place of explicit
  start/end bounds. Existing 60-second export limits apply. Use
  `view --sections --start S --end S` for bands in a custom range.
  Views with `sections.json` show bands automatically.
- MCP `reconstruct_sections` takes `workDir`, optional `action` (default
  `list`), and an `entries` array for mutations. MCP `reconstruct_view`
  and `render_compare` take `section` instead of `start` and `end`. View
  also takes `sections: true` to show bands with explicit bounds.
- Java clients use `ReconstructionSections.load(Path)` for the effective
  hierarchy, proposal, and diagnostics.

## Identify and assign voices

The `voices` stage groups role-stage notes into named function voices.
The top-level `instruments` array records the MIDI part, program and mapped
stem for each source instrument. Tonal voices reference them with
`instrumentIds`. A split into line and chords retains the same instrument
for both. A manually merged voice may reference more than one source
instrument, and each note keeps its source identity until assigned explicitly.
A voice can carry different roles over time. Parts with a recurring melody
or counter line and repeated stacked accompaniment can propose separate
line and chord voices. The detector checks attack patterns within musical
sections and uses source-time onset clusters, including for steady-grid
projects. Repeated simultaneous attacks in pitch bands separated by an
octave can also establish a lower and upper voice.

For otherwise ambiguous parts, accessible tonal stems can supply acoustic
evidence. The proposal checks whether pitches repeatedly sound more
strongly in different mapped stems, or whether the same pitches have
distinct harmonic profiles across sections of one stem. If the evidence
is weak or the audio is unavailable, the part can stay together. Drum
identities follow General MIDI drum classes.

`voices.json` records role and section counts, singleton and stacked
attacks within the source part, available stem evidence and heuristic
confidence. Role-stage drops remain in the proposed membership for
review. IDs use the MIDI part and the evidence for the split, such as
`part-0-line`, `part-0-chords`, or `part-0-lower`. Replay applies the
authored voice journal to these IDs. If a prior whole-part ID disappears
after a split, the edit is reported as orphaned. Review the new proposal
before targeting a replacement ID. Resume from `voices` after changing
section boundaries used by the proposal.

```sh
java -jar "$jar" reconstruct voices song-work
java -jar "$jar" reconstruct voices song-work rename   '{"id":"rename-lead","author":"human","voiceId":"part-0","name":"Lead instrument 1"}'
java -jar "$jar" reconstruct voices song-work assign   '{"id":"hat-noise","author":"agent","voiceId":"part-2-closed-hat","assignment":{"laneType":"NOISE","instrument":"closed-hat"}}'
java -jar "$jar" reconstruct run --from voices song-work --keep-voicing
java -jar "$jar" reconstruct view song-work --start 0 --end 8 --color-by voice voices.png
```

List and edit commands evaluate the current proposal and journal. Each
entry uses a unique `id`, an `author` of `human` or `agent`, and a `voiceId`.
The action determines the remaining fields.

| Action | Fields |
| --- | --- |
| `create` | A new `voiceId`, `name`, and `kind` of `tonal` or `drum`. |
| `rename` | The new `name`. |
| `merge` | `targetId` receives the source voice's notes. Clear one assignment before merging different instruments. |
| `split` | A new `targetId`, `name`, and note selection from the source voice. The new voice inherits its assignment. |
| `move` | A note selection to move into `voiceId`. |
| `assign` | `assignment: {"laneType":"FM","instrument":"VARIANT_ID"}` or `clear: true`. |

Use one selection for a move or split. Supply `noteIds`, `selectors` (an
array of `{at, pitch, part}` point selectors with 20 ms tolerance), or a
half-open `span: [start, end]` with optional `part`, `pitchMin` and
`pitchMax` filters. Selectors and spans default to source seconds and accept
`timeBase: "project"`. A mutation accepts one JSON object or an atomic array
for that action. `voice-edits.json` stores ordered entries with an `op`
field. It survives retranscription and replay. If a target disappears,
the whole entry stays in the journal and appears under `issues` for repair.

FM, PSG and DAC assignments select a catalogue variant of the corresponding
FM patch, volume envelope or sample kind. NOISE uses PSG3 with `fixed`,
`closed-hat` or `cymbal` voicing. Tonal voices use FM or PSG. Drum voices use
DAC or NOISE. Allocation keeps the usual role preference within the requested
type and records unavailable notes or incompatible exact-lane pins as
reductions. A melody voice assigned to FM keeps ordinary FM1 overlap
handling before using another FM lane. An explicit assignment follows each allocated note through
lane changes. It takes precedence over lane and scoped instrument settings.
Assigned FM patches preserve their native release rates across lanes.
Gain, gate and effects come from the source instrument and its applicable
lane overrides. Instrument changes involving an assignment start a new clip
when needed. The assembly report counts applied assignments by lane and
any extra clips from these boundaries in `voiceAssignmentExtraClips`.

MCP `reconstruct_voices` takes `workDir`, optional `action`, and an `entries`
array for mutations. Call `reconstruct_stage` with `stage: "voices"` to
write the artifact alone, or resume with `reconstruct_run` from `voices`.
After the stage runs, `reconstruct_trace` gives each classified note's voice
ID and name. `reconstruct_view` with `colorBy: "voice"` returns the same voice
colors as the CLI view, with an ID-to-color legend and note-to-voice mapping.
Java clients can call `ReconstructionVoicePlan.load(Path)` and append typed
edits atomically with `ReconstructionVoicePlan.edit(Path, List<Edit>)`.

## Trace and reproducible inputs

Use `reconstruct trace WORK_DIR --by-onset --start S --end S --part N`
to list one line per part and attack cluster in source seconds. Attacks within
20 ms of the first onset are grouped. Notes show their pitch, role, lane,
fate and stable ID. Use `--json` for structured clusters or `byOnset: true` on
MCP `reconstruct_trace`. `--time project` (MCP `time: "project"`) selects
project-clock bounds. Clusters report both clocks.

`reconstruct trace WORK_DIR` joins source, score, structure, roles,
allocation, and voicing evidence by stable note ID. Source and project
seconds accompany arrangement ticks. Ticks on allocation and voiced gates
are the actual intervals. Earlier ticks are rounded views of unquantized
notes. Top-level `timing` retains the original gate. `effectiveTiming`
uses the primary voiced gate, spanning the voiced steps of a figure.
It falls back to allocation and then the latest available note-stage gate.
Trace time filters match both the original and effective intervals. Repeat
corrections retain their vote evidence, and a thinned attack links to the
retained note whose gate it extended. Hardware
conflicts carry `competitorNoteId` for a single blocker and
`blockingNoteIds` when several lanes are occupied. A shortening
links to the incoming attack that cut the release.

A summary chooses one fate per note. Removal takes priority, with
`merged` used when another note absorbed the attack. A shortened gate
takes priority over a move. Counter and harmony notes placed on PSG
count as moved from their primary FM lanes. The individual stage entries
remain visible when several decisions apply. New structure and roles
artifacts record input note hashes so an edited note can be distinguished
from the older version that reached allocation. Assembly records voiced
gates and the articulation and bend choice can be inspected independently
of the allocated gate. Older artifacts remain readable using the timing,
reductions, and bends they recorded.

`reconstruct trace-diff OLD_WORK_DIR NEW_WORK_DIR --span START,END`
compares the chains by ID. The optional span uses source seconds and
flags changes outside the interval. A change in a gate, role, lane,
merge partner, or applied bend can appear even if the summary category
stays the same. `lanePermutations` summarizes contiguous lane changes
outside the span, including the first diverging note on each original lane
and suggested `pinLane` edits. `voicingChanges` compares lane variants,
gain and part gains independently of note fate. Scoped entries include the
lane, selector, action, and before/after fields. `compiledTimeline` counts
added, removed or changed compiled events, including clip reset commands,
and gives the first affected project tick and lanes. `renderedPcm` counts
changed sample frames in saved renders and gives the first affected frame.
Both summaries cover the area outside `--span`, or the whole project when
no span is supplied. They can report different results because chip and
resampler state can carry through the edit boundary. When matching-length
reconstruction WAVs differ after the span, `renderedPcm.psgTail` compares
soloed re-renders and reports affected PSG lanes and their causes. It checks
resolved PSG writes in presentations with changed PCM and inspects the
chip state at the first differing presentation. The PSG tone counters and
polarities keep running during rests. Writing the next note's period sets
the reload value without resetting its phase. S3K clip setup can restore
driver settings, but has no command to restore the chip's oscillator phase.

A fresh staged run writes `manifest.json` with the build identity,
resolved options, input paths and hashes, and references to content-addressed
note copies in `snapshots/`. A resumed run records its start stage and
override while keeping the original input recipe. When first resuming a
pre-manifest run, the saved score can supply a parsed external-note
snapshot and its provenance. Original bytes are retained for new runs.
`reconstruct rerun WORK_DIR NEW_WORK_DIR` verifies input hashes at the
stored path or snapshot and checks any repeated `--search DIR` roots for
missing bytes. The replay starts from transcription using the saved options
and original external notes. Replay copies `edits.json`, `roleplan.json`,
`sections.json` and `voice-edits.json`.
The manifest records the plan's `rolePlanSha256`. Use `--keep-voicing` to
carry accepted voicing settings. Manual changes to derived stage artifacts
must be expressed through the corresponding recipe inputs to replay.

Studio's **Open as arrangement** exposes the final project to the
arrangement editors. Saving a musical hand edit sets the optional
`detachedFromReconstruction` boolean in the project JSON. Export reads that
project directly. Assembly refuses to replace it without
`--overwrite-detached`. Studio Apply binds approval to the shown arrangement's
revision and can preserve a copy. In-place rerun with the explicit flag
rebuilds from the current saved score inputs. Replay to a new directory
leaves the hand-edited source arrangement intact. The
[headless guide](../../headless/README.md#export-project-output) lists the
export formats and MCP options.

Allocation prefers prior lanes when they have the same hardware priority
and can accommodate the attack. `allocation-affinity.json` records the lane
seed used for the last allocation so replay can reproduce that decision.
`allocationAffinityChanges` in the report lists each note ID whose chosen
lane differs from a fresh allocation, with its previous-affinity lane and
fresh lane. Replay of legacy directories uses their saved allocation as
the seed.

Assembly saves the first clip starts in `assembly-layout.json` and retains
them on subsequent assembly and replay. A note crossing an anchor keeps its
gate. Legacy directories seed the layout from their existing arrangement.
Keep these replay artifacts with the private recipe.

## Transcribe unmatched tonal stems

On a fresh `run` or `transcribe`, pass `--transcriber NAME` with repeatable
`--transcribe-unmapped ROLE=WAV`. This creates part IDs after the MIDI IDs and
keeps the WAV associated with its new part for voice fitting and salience.
`import --transcribe-unmapped NAME` adds the corresponding run options for
unmatched keyboard, synth, bass, guitar, strings, brass, and woodwind stems.
Use a polyphonic adapter that supports the selected roles.

Same-pitch stem fragments are joined when pitch-band energy remains continuous
across the boundary. Unisons mostly covered by tonal MIDI are deduplicated,
including across MIDI re-attacks. Octave removal also requires weak energy
at the added pitch relative to the other octave. Automatic PSG matching for
added stems prefers envelopes that sound at note-on and avoid silent pulses.
When tonal MIDI is present, the added notes supplement the automatic lead
and bass as backing. Explicit role choices can promote them.
Strong sustained upper pitches can supplement polyphonic transcriber output
where a local spectral peak dominates plausible lower fundamentals. New
notes are marked `audio-transcribed`. The part's manifest entry records
`addedStem`, the adapter and role, the stem SHA-256 and the saved transcriber
output. Replay uses that snapshot with the verified aligned stem.

## Chord audit and channel contention

`describe` reports `chordAudit` after allocation. Assembly adds the audit to
`report.json`, including losses from unavailable catalogue matches. Each
loss has a reason, part, pitch, and source-clock onset. Audio-only runs
also include the allocation role and any recorded competing note IDs.
Studio shows the reason and competitors when you select a missed note in
Detail. MCP clients can read the audit with `reconstruct_describe` or
inspect a single decision with `reconstruct_trace`. Song counts and
section counts use the effective section-level spans from `ReconstructionSections`.
The categories are doubling, root in bass, fifth, extension, third, seventh,
and onset-cluster top voice. Root and chord intervals are inferred from
sounding pitches with a preference for the assigned bass.

When a run includes an added stem and backing lanes are full, the allocator
preserves top voices, then thirds
and sevenths, then other tones. Bass roots, unison and octave doubles, and
fifths are lower priority. Pitch-band level in aligned stems breaks musical
ties when available. Explicit pins, lead and bass lanes, and DAC rules retain
their authority. A short higher-priority attack can interrupt a sustained
backing gate and leave room for a separate re-attack. The allocation records
the prefix as `pad-yield` and the new gate as `pad-resumed` when it fits.

## Persistent musical edits

`edits.json` is a versioned object with an ordered `edits` array. Each intent
has a unique `id`, a free-text evidence `reason`, and an `op` with the fields
below. Order applies within each stage, with later role or lane settings
taking precedence. Intents use source seconds by default. Set
`"timeBase":"project"` on an intent to use project seconds for its span,
selector, gate, and curve. Pitch is a MIDI integer.

Studio uses the same versioned `edits.json` as `reconstruct edit`.
An intent may include `"author":"human"` or `"author":"agent"`. The field is
optional for legacy journals and does not change the reconstruction operation.
Studio writes its new intents with `"author":"human"`.

An intent's optional `status` is `proposed` or `accepted`. Omitted status
means accepted. A proposal requires `"author":"agent"` and may include a
`group` string and an `evidence` object with `text` and a work-relative `path`.
Stage runs consume accepted intents only. They record the shadow dry run of
the proposals together in `proposal-results.json`, including outcome statuses
and the source-time note outlines. This report is separate from
`edit-results.json`, which records the built state. Accept preserves the
intent's journal order and makes it eligible for the next rebuild. Reject
removes only the selected proposals. See the
[CLI and MCP commands](../../headless/README.md#review-edit-proposals).

| Op | Fields | First stage |
| --- | --- | --- |
| `insertNote` | `part`, `start`, `end`, `pitch`, optional `velocity` (default 96), `role`, `lane`. The effective note has ID `edit-<id>` and source `edit`. | `transcribe` |
| `deleteNote` | `noteId` or a point selector. When the score has no match, roles can remove a generated note such as a `lead-audio-added` attack. | `transcribe` |
| `setNote` | `noteId` or a point selector and one or more of `pitch`, `start`, `end`, `velocity` (1–127), `noExtension` (boolean). Changing pitch or timing clears inferred motion, bends and retriggers tied to the old attack. | `transcribe` |
| `transpose` | `noteIds` and a signed, nonzero integer `semitones`. Applies in journal order and keeps note IDs and timing. Skips drum parts and rejects moves outside the optional `laneType` range (`FM` by default, or `PSG` for tone lanes). PSG pitches above the FM limit are allocated to PSG. | `transcribe` |
| `splitNote` | `noteId` or a point selector and `at` strictly inside the gate. Both halves need a project tick. Optional `pitch` retunes the second attack. The source attack is replaced by `edit-<id>-left` and `edit-<id>-right`. | `transcribe` |
| `mergeNotes` | At least two same-part tonal attacks selected by `noteIds`, point `selectors`, or `span`, `part` and `pitch`. The earliest attack keeps its ID, pitch and velocity and extends to the latest release. Set `allowDifferentPitches: true` to hold the first pitch, or `glide: true` to slide to each changed pitch. Each generated pitch change needs four project ticks between attacks. With `glide: true`, supply explicit `points` to use the last note’s pitch as the base for the measured curve. With explicit points, optional `start` sets the merged attack in the edit’s clock. The resulting slide is routed to FM. | `transcribe` |
| `setVelocity` | `noteIds`, or `span` with `part` and optional `pitchMin` and `pitchMax`. Choose either absolute `velocity` (1–127) or a nonnegative `scale`. Scaled values round to the nearest integer and clamp to 1–127. | `transcribe` |
| `setRole` | A selector and `role`. A span, ID list or point list can filter by `fromRole` after earlier edits. Optional `lane` also pins the note. | `roles` |
| `swapRoles` | `span`, `part` and distinct tonal roles `from` and `to`. If any `from` notes match, move them to `to` and assign existing `to` notes within the same part and span to harmony. | `roles` |
| `pinLane` | A selector and either an exact `lane` or `laneType` (`FM`, `PSG`, `NOISE`, `DAC`). | `roles` |
| `unmerge` | A selector. Restores the written attacks and gates of the selected thinning groups, including the anchors and other members. | `roles` |
| `bend` | `noteId` or a point selector from the transcription score, and `points` of increasing `[seconds, semitones]` pairs inside its gate. Place after timing or pitch edits to the same note. The FM encoder fits the curve to the playable gate and records the applied result. A point up to one project tick past the gate clamps to the gate end. | `transcribe` |
| `glide` | `fromNoteId` and `toNoteId` identify two pitched score attacks in the same part. The first gate ends at the later attack, which retains its own note. Optional `seconds` gives the sliding portion of the gate (default up to 0.12 seconds). `shape` is `linear` (default), `easeIn`, `easeOut`, or `smooth`. The first note needs an FM lane for the bend to play. | `transcribe` |
| `arpeggio` | `noteIds` or `span` with `part` and optional pitch bounds. `rate` is 1–127 driver ticks per step (default 2). `order` is `up`, `down`, or `up-down` (default `up`). | `allocate` |
| `chordInstrument` | Same selectors as `arpeggio`. Replaces the selected chord with a catalogue-derived multi-carrier FM voice. | `allocate` |
| `voiceEdit` | A `voiceEdit` object with the fields of a [voice journal entry](#identify-and-assign-voices), including its own `op` and `voiceId`. The outer intent supplies `id` and `author`. The voices stage replays these after `voice-edits.json`. | `voices` |
| `noteVoicing` | `noteIds` and at least one of `instrument` with `laneType`, `vibrato` with native F0 fields, `vibratoEnvelope` for staged S3K vibrato, or `modulationOff: true`. Instrument IDs use the same catalogue and named noise presets as voice assignments. Vibrato here requires FM. | `assemble` |
| `layer` | `noteIds`, point `selector` or `selectors`, or `span` with `part` and optional `pitchMin`, `pitchMax` and `fromRole` filters. Set `mode` to `echo`, `chorus`, `double` or `off`. Copies require `laneType` (`FM` or `PSG`). | `assemble` |

At assembly, `noteVoicing` resolves each ID on its current chip, reports
missing or incompatible targets in the edit results, and applies the latest
instrument and modulation independently. A compatible note-scoped instrument
overrides the source voice instrument. A per-note lane pin can take the note
to a different chip, where the voice's instrument is inapplicable.

A `layer` consumes a free channel for the copy's full gate. The latest
layer intent for each selected note takes precedence over lane policies.
`off` disables the copy and masks policies for that note. Echo delays the
copy by `delayFrames` (1–600, default 14, at 60 frames/s). With chorus, set
`detune` in signed FM driver units (−128 to 127, default −3). PSG chorus
translates that offset to PSG period units. With double, set `octave` to
−1, 0 (default), or 1.

Set either `attenuation` (0–127 carrier TL steps) or `levelDb`
(−95.25 to 0). The default is 11 TL for echo and 12 TL for chorus and
double. The conversion rounds `-levelDb / 0.75` to the nearest TL step.
FM uses the dry patch and clamps to its carrier volume floor. An FM
copy of a PSG note uses its fitted FM sound when available, with a catalogue
FM patch as fallback. PSG uses a held tone and matches the requested relative
level in its coarser native volume steps. For echo, optional `volumeDecay`
lists objects with increasing relative `frame` offsets (1–600) and
nondecreasing `attenuation` targets. Each target is relative to the dry
note, and steps at or past release are omitted.

```json
{"id":"answer-echo","op":"layer","author":"human","reason":"Echo the selected lead attacks",
 "noteIds":["LEAD_NOTE_ID"],"mode":"echo","laneType":"FM","delayFrames":10,"levelDb":-9}
```

Dry runs and proposals report a result for each resolved note. A busy lane,
an unsupported pitch or a source without an independently playable tonal
gate produces a skipped result. Layers copy rendered bends over their
own gates, so an echo can finish its slide after the dry note releases.
FM echoes and octave doubles replay the native pitch and vibrato commands
where representable. Detuned copies apply a pitch ratio derived from the
requested offset at the dry attack pitch. Sampled copies include any vibrato
that played on the dry bend. PSG updates the nearest playable period each
driver tick and clamps to its 10-bit range. Each rendered bend copy reports
`curve.status`, `curve.rmseCents` and whether it meets the 15-cent RMS tolerance.
Quantization or range limits can produce an approximation and the layer
still plays. See `noteLayers` in `report.json` and `view`, and `layers` on the source note
in `trace`, for the layer status, lane, delay and gate. The journal is replayed
when rebuilding from any earlier stage.

Use `"selector":{"at":43.09,"pitch":80,"part":0}` to match a single
attack within 20 ms of the source onset. Zero matches or multiple matches
report `invalid`. For span or ID selection on `setRole`, `unmerge` and `pinLane`,
use a nonempty `noteIds` array or `span: [start, end]` with `part` and
optional inclusive `pitchMin` and `pitchMax`. Span fields may be in a
`selector` object or at the top level. Spans include attacks at the
start and exclude attacks at the end. For `setRole`, `pinLane` and `mergeNotes`, `selectors` is an array
of point selectors. A common `part` can be set on the intent. Each point must
resolve uniquely. An ambiguous or missing point is reported with its own
outcome while resolved points are applied once per note. `mergeNotes` is
atomic and requires every selector to resolve and at least two distinct targets.
For a merge span, use a single exact `pitch`.

```json
[
  {"id":"cut","reason":"separate the attacks","op":"splitNote","noteId":"held-note","at":12.85,"pitch":65},
  {"id":"sustain","reason":"join chord fragments","op":"mergeNotes","part":0,"pitch":64,"span":[15.2,16.8]},
  {"id":"backing","reason":"move the backing to PSG","op":"pinLane","laneType":"PSG","part":0,
   "span":[18,20],"pitchMin":55,"pitchMax":67}
]
```

Split and merge author new gates and clear the attacks' inferred motion.
Assign roles and pins to the resulting IDs when needed. A split refuses to
discard an existing authored bend or note-scoped voicing override. A merge
reports `conflict` if another intent individually changes a removed attack,
changes the survivor's gate or bend, or a saved voicing override targets a
removed note. An unfiltered role or level span that covers the entire group
can still apply to the sustain. The first applied merge wins overlapping
merge targets. For glide merges, the pitch changes in the final 0.12 seconds
before each successive attack, or over the available interval when shorter.

Span operations also accept `voice: "top"`, `voice: "bottom"`, or a zero-based
rank from the highest pitch. They group eligible attacks within 20 ms of
each cluster's first attack, in the same part, in source time. Equal pitches
sort by attack time and stable ID. A rank outside a cluster skips it.
`setRole` can assign its remaining eligible notes a role with `others`.
`fromRole` applies to the ranked target. For example:

```json
{"id":"top-line","reason":"lead is above the chord","op":"setRole","part":0,
 "span":[48.9,51.7],"voice":"top","role":"melody","others":"harmony"}
```

Set `noExtension: true` to keep lead legato and stem-backed release
extension from stretching the attack. A later `noExtension: false` clears
that flag. Explicit pitch and timing edits still protect their authored
gate. Velocity-only edits preserve the attack's existing timing and
motion evidence.

Roles are `melody`, `bass`, `counter`, `harmony`, `drums` and `drop`.
An explicit lane can route a pitched role to any FM lane
or to an eligible PSG lane. Percussion can use DAC or PSG3. Hardware pitch
limits and FM-only mode still apply. An overlapping unpinned gate can be
shortened for a pin. Earlier pinned attacks win overlapping gates, and the
earlier intent wins ties at the same tick. The trace records the winning
competitor when the other note is shortened or dropped.

A type pin prefers the note's previous lane if it belongs to that type
and is free. It then tries the role's preferred lanes within the type and
any other free lane of that type. If all are busy, it drops the attack and
reports the edit and blockers in allocation and trace. A kept type pin
protects its gate just like an exact pin. PSG tone and noise share PSG3.

| Requested type | Tonal parts | MIDI drum parts |
| --- | --- | --- |
| `FM` | FM1–FM5. Authored bends and fast figures use FM. | Unsupported by reconstruction's drum voicing, which fits DAC samples or uses PSG noise. |
| `PSG` | PSG1–PSG3 tone. Single attacks at MIDI 45 or higher. | Invalid. |
| `NOISE` | PSG3 periodic noise clocked by tone 3. Single attacks whose pitch can be tuned within 50 cents. | PSG3 white noise clocked by tone 3. GM hats and cymbals use the short or long cymbal voicing. |
| `DAC` | Invalid. | DAC sample triggers. |

Both PSG types are unavailable in FM-only mode. Authored bends require FM.
For tonal `NOISE`, S3K F3 E3 selects a periodic 16-step LFSR and the
sequencer writes PSG3's 10-bit tone period as its clock. The rate is
`PSG tick rate / (32 × period)`. Reconstruction uses the signed detune
range around the known period 16 and accepts periods 2–143 within the
tuning tolerance. PSG3 cannot play a tone and noise simultaneously.

Use `reconstruct edit WORK_DIR add JSON | add --file BATCH | list | remove ID`.
Inline `add JSON` takes one intent object. Pass arrays through `add --file BATCH`.
Use a string such as `"bars": "6-7"` in `roleplan.json`.
MCP offers the same operations through `reconstruct_edit`. The batch file
accepts an array of intents or a versioned edits object. MCP add takes
either a single `edit` or an `edits` array. Entries append in order, and a
malformed intent or duplicate edit ID
rejects the whole batch before writing. Resume from the returned `from`
stage. Use `add --dry-run JSON` or `add --dry-run --file BATCH` (MCP
`dryRun: true`) to resolve a batch in memory. The preview lists stable IDs,
incoming and resulting roles, satisfaction, and both attack times. It also
reports selector failures and leaves the work directory untouched. Split and
merge expose source gates and pitches as `resultNotes` and list replaced
attacks as `removedNotes`. A lane-type preview runs allocation in memory
and includes `laneType` and the selected `lane` in its allocation outcomes. A
`setRole` target already in the desired role after earlier edits can return
`satisfied` even when `fromRole` differs. MCP add and remove accept
`rerun: true` to rebuild once and return a trace diff. The diff flags changes outside the combined range of the
requested spans and targeted gates. For example, a batch can contain these
two intents:

```json
[
  {"id":"lead","reason":"counter line carries the phrase","op":"swapRoles","span":[43,45.6],"part":0,"from":"counter","to":"melody"},
  {"id":"hats","reason":"closed hats dominate this phrase","op":"setVelocity","span":[43,45.6],"part":2,"pitchMin":42,"pitchMax":42,"scale":0.32}
]
```

`edit-results.json` records outcomes by stage, and assembly includes them in
`report.json` as `editResults`. An absent target reports `orphaned`, including
any missing IDs in a partially matched list. A target that exists but is
incompatible with the intent reports `invalid` with a field-specific detail.
`run` and `rerun` summarize invalid and orphaned outcomes on stderr.
An unplaceable pin reports `conflict`, with competitor IDs in the allocation
trace. A conflicting merge names the edit or voicing scope that blocked it.
The trace includes `edit:<id>` reasons alongside the underlying fate detail.
Consumed merge members link to the retained attack through
`scoreDecision.successorId`.

### Retrigger a held note

Use a single `retrigger` intent with an ordered list of `attacks`, each with an
`offset` from the original onset and a positive `gate`. Both are in seconds
on the edit's `timeBase` (source by default). Each attack needs its own
non-overlapping project gate. The original ID identifies the first attack
and dry-run lists the remaining IDs as `edit-<id>-1`, `edit-<id>-2`, and so
on. Use those IDs for later layer or voicing edits.

```json
{"id":"pulse","reason":"Match repeated attacks","op":"retrigger","noteId":"NOTE_ID",
 "attacks":[{"offset":0,"gate":0.06},{"offset":0.083,"gate":0.06}]}
```

For an even run, replace `attacks` with `"rate":0.083,"count":4,"gate":0.06`.
The rate is the interval between attacks and the optional gate defaults to
that interval. An explicit attack can override `velocity`. Optional `role`
and `lane` route the additional attacks. Rebuild from transcribe.

### One-channel chord voicing

These edits select playable tonal notes of one MIDI part that attack on the
same quantized driver tick. Select exact IDs to exclude a melody or an
independent octave double from a chord. A span can use `pitchMin` and
`pitchMax`, and selects attacks at its start through just before its end.
Use `"span":{"section":"chorus-1"}` for a named section, or `"timeBase":"project"`
for project-time bounds. Span selections skip single attacks. An attack at
the end of the span is outside it. A selected held gate can extend past the span.

```json
{"id":"chorus-arp","reason":"Free lanes for the counterline","op":"arpeggio",
 "part":0,"span":{"section":"chorus-1"},"rate":2,"order":"up-down"}
```

```json
{"id":"hold-triad","reason":"Keep this triad simultaneous","op":"chordInstrument",
 "noteIds":["C4_NOTE_ID","EB4_NOTE_ID","G4_NOTE_ID"]}
```

The first successfully selected edit claims each chord tone. The allocator
uses the highest-priority selected attack to choose one lane and puts the
other lanes back into circulation. It still observes lane priority and
hardware limits. If no compatible lane can take the group, it reports a
conflict and tries the original notes individually. Lane-type pins on any
member constrain the entire group. A chord instrument needs FM. An arpeggio
can use FM, PSG tone, or pitched noise if every tone fits the requested type.
Conflicting lane types, exact lane pins or source voice instrument assignments
leave ordinary allocation in place.
Authored bends and detected fast runs or retriggers are incompatible. A chord edit
controls pitch, gate and modulation during playback, inheriting the
representative note’s attack patch, gain and minimum release rate. Individual
velocity, bend, vibrato, gate shaping and copy effects are not carried into the
combined performance. Remove or narrow an incompatible lane-specific
override if allocation changes the set of notes it addresses.

An arpeggio keys on at each written step. A 2-tick step is about 33 ms at
reconstruction tempo. Up-down turns around without repeating its endpoints.
Each tone leaves the cycle when its gate ends. The last surviving tone holds
to its assigned release. Groups that cannot present all their pitches at the
requested rate are refused. `report.json.arpeggioExtraNoteBytes` counts the
extra pitch/duration bytes over a single held note on the same lane and gate.
`bankBytes` includes all stream, command, allocation and voice-table changes.

FM chord voices use two carriers in algorithm 4, three in algorithm 5, or four
in algorithm 7. Major (4:5:6), minor (10:12:15), sus2 (8:9:12), sus4 (6:8:9),
power chords (2:3), and octave stacks use integer ratios. Inversions and
doubled registers work when reduced multiples fit 1–15. The fitter chooses an
S3K note and signed detune byte with at most 18 cents of error per tone.
For ambiguous suspended chords it tries a root in the bass first. Unsupported
qualities (including seventh chords), excessive spreads and base pitches
outside the driver’s tunable range leave the original notes in place.
The carrier envelope and level come from the lane’s catalogue patch, with
headroom for multiple carriers. This changes the FM topology and key scaling.
Patch changes follow chord quality and carrier releases. Staggered releases
reattack the remaining pitches with the released carriers muted.

Use `reconstruct edit WORK_DIR add --dry-run JSON` to check the selection,
then save it and run `reconstruct run --from allocate WORK_DIR --keep-voicing`.
The same edits work through MCP `reconstruct_edit` and Java
`ReconstructionEditCommand.run`. These operations are replayed from
`edits.json` on rerun. Inspect `edit-results.json`, `report.json.chordVoicings`,
`trace` and `view` for applied groups, refusals, the shared lane and the
assembled gates of each tone.

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
`source` or `project`. `roleplan.json` supports both with an explicit
`timeBase`. Rules use `startSeconds` inclusive and `endSeconds` exclusive.
New templates use source seconds. A time-only rule can omit `sections` and
`bars` and also defaults to source seconds. An existing section- or bar-scoped rule with no `timeBase` still inherits
the structure clock. The roles stage reports time-bounded rules as source
and project spans with half-open project tick ranges. MCP returns them in
`rolePlanTimes`.
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
| `durationUs`, `frames`, `ticksPerSecond` | Requested interval in microseconds, its ceiling in 60 Hz presentations, and the target clock’s nominal tick rate. |
| `inputs` | Absolute `midi`, `wav` and `catalogue` paths. Also stores `name`, `ticksPerBeat`, `keepOctaves`, `fmOnly`, `wholePartVoices`, `audioRefine`, `stems` (part ID to absolute WAV path), optional `candidates`, and `harmonyMaxHz`. |
| `bars` | Musical grid in elapsed seconds. Each bar has `id`, `start`, `end`, and `complete`. A pickup, meter change between nominal boundaries, or cut at the requested end can produce a partial bar. |
| `tempo` (optional) | Grid summary with BPM, beat phase, bar offset, beats per bar, and evidence. `source` is `tracked`, `estimated`, `override`, or `midi` when the PPQ grid is retained. |
| `beatMap` (optional) | Editable `beats` in source seconds, zero-based `downbeat` phase modulo `beatsPerBar`, and `confidence` from 0 to 1. The first or last anchor may extend past the requested interval. |
| `midiBars` (optional) | Original PPQ windows kept for role continuity when an automatic estimate supplies the musical grid. |
| `source` | Reference context for matching. `parts` has `id`, `name`, `program`, and `drums`. `notes` has `partId`, `pitch`, `velocity`, `startUs`, and `endUs`. `warnings` contains MIDI parser messages. |
| `sourceCounts` | Original note count per MIDI part inside the interval. |
| `notes` | Editable notes after clipping to the interval and the existing pitch-range and systematic octave-double cleanup. Sources include `midi`, `audio-refine`, `transcriber`, `external` and `edit`. |
| `reductions`, `pitchChanges` | Original preparation decisions and optional audio-refinement evidence, also exported in the report. |

Opt-in external transcription supplies melodic note replacements or additions
before the score is written. It preserves the original MIDI reference context
in `source`, including drums and the evidence used for the tempo grid.
`inputs.externalNotes` is an optional map from part ID to the imported note
snapshot and its provenance. The assembly report carries the provenance
under `externalTranscription`. A resume from `transcribe` reapplies the
snapshot, and later starts use the saved stage artifacts. Notes marked
`transcriber` enter structure as score notes. The existing `--candidates`
interface still supplies `external` repeat-consensus evidence.
See the [headless guide](../../headless/README.md#external-transcription).

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
that case. If the tracked grid is wrong, correct the input MIDI tempo map
or use the explicit `--tempo` and `--bar-offset` options in a new run.
Changes to the grid can also affect project timing under the steady-tempo
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
then share the project clock. Allocation selects the arrangement tick whose
playable presentation is nearest, clamps releases, and gives each note at
least one tick. S3K ticks on every presentation. S1 and S2 skip periodic
duration updates according to the [target clocks](driver-support.md#target-built-reconstructions).
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

### Stem-backed fast figures

The staged transcriber reads aligned, mapped tonal stems before writing
`score.json`. The run detector samples pitch every 2.5 ms using two
short periodicity windows. On mapped lead audio it also considers an
interpolated spectral ridge supported by a fundamental and its octave.
It requires stable centers, adjacent moves of one or two semitones,
and at least four notes near one to six 60 Hz frames each. It accepts
one passing reversal in a longer figure. A stable core must occupy
nearly half of each rounded pitch span. This rejects quantized pitch
crossings in a continuous glide. Dense backing and very short, low
notes can still mask the lead.

On an explicitly declared lead part, a second detector measures the
combined short-window envelope of a held note's first four partials.
It searches for a sequence of at least six strong rises, roughly two
to six frames apart, with consistent spacing. That provides evidence
of separate attacks even when the pitch stays constant. Eligibility
uses the held note's pitch and favors the stronger attack among
simultaneous overlapping pitches.

Pitch-changing figures require an overlapping held note at the first
pitch. Each changed pitch also needs a transcribed attack in the same
register within 35 ms that covers at least 60% of the detected step.
Already aligned transcribed attacks take precedence. The score stores
accepted scales as one `fast-run` note with confidence and ordered
source-time `run` steps. Structure projects the complete figure to the
project clock and excludes it from note-level repeat voting. Roles and
allocation keep it atomic. Allocation reserves one presentation per
step on the assigned lane, merging the shortest steps when necessary.

Same-pitch candidates are stored separately in `score.json` as
`retriggers` keyed by anchor note ID. Competing explicit attacks can
veto the burst. The original note goes through structure, role
selection, legato, allocation and voicing. During assembly a burst
adds key-ons only when its original note still owns the entire burst
on FM1 at the same MIDI pitch. It preserves the fitted patch, the
note's initial key-on and its voiced release. The next allocated
note and other lanes keep their existing placements. An onset within
one presentation of the original key-on can reuse that attack.

`reconstruct describe --json` gives figure evidence on the current
project clock. The report records source evidence and the allocated
lane, span and retained step indexes for figures used in the render.

For figure evidence, `figureVoices` keeps separate spectral pitches
whose changes follow the ordered steps. For retriggers, it requires a
transcribed companion pitch and independent partial-envelope rises
within one presentation of the anchor. Voice evidence shares the
figure's boundaries. The highest supported voice is tagged `lead`
and the others `harmony`. The original placement remains the playback
anchor. Evidence for extra voices is available in the score, describe
and report artifacts. Octave partials alone leave independent doubling
ambiguous.

For a mapped explicit lead, the roles stage can also restore already
transcribed attacks in a locally monophonic phrase. It requires a
consistent mixture of short and long attack intervals, audible onsets
in the matching pitch band, and no competing selected lead. Restored
attacks carry `stem-phrase-attack`. Use `--no-fast-runs` on initial
staged transcription to disable all three checks for an A/B comparison.

### Stem-backed pitch bends

A mapped tonal stem can supply a `bend` on a prepared note. The detector
tracks the attack's spectral ridge inside the written gate and uses agreeing
harmonics to reject unrelated pitches. The score stores signed-cents control
points in source seconds, bend direction or shape, initial delay, and
confidence. Structure warps the points together with the gate. The describe
grid marks upward and downward motion with ↗ and ↘.

S3K transcription also tracks backward from a stable landing in the mapped
stem. This finds upward scoops that begin below the attack window and can
join short, ascending fragments that follow one continuous ridge. A
steady pickup can precede the rise. When the stem supports a continuous
ridge and a single attack, the curve holds the pickup pitch from its onset
until the glide begins. A merge proposal can join an adjacent written pickup
and landing. A pitch jump, gap, or renewed attack keeps the pickup separate.
The detector can still propose a supported residual rise on the landing gate.
When a continuous ridge crosses a drop in energy at a fragment boundary,
the join stays in review to allow for another pick on the sliding string.
The merged gate keeps the first ID and
uses the landing pitch as its nominal pitch. A scoop can adjust the written
start with the optional `start` field on a `bend` edit. Curve points use the
same `[seconds, semitones]` format on `bend` and `mergeNotes`.

High-confidence approaches on the selected lead or a mapped guitar part
apply automatically. An automatic approach can guide a search
for the same contour in other written chord voices, including octaves
with a weaker ridge or slightly later transcribed onset. Each voice
needs its own matching ridge through the rise and landing. The anchor
uses FM, and the other chord voices can use FM or PSG tone. PSG plays
the measured approach as tied period changes.
A staged vibrato envelope takes precedence over an inferred bend on the same FM gate.
Other candidates enter `edits.json` as
`stem-pitch-approach` proposals. Use
`reconstruct proposals list WORK_DIR --group stem-pitch-approach` to inspect
them. Retranscribe to recover approaches from existing work. Accepted and
rejected decisions carry forward on replay.

For a bend on FM1 or an authored or recovered bend on another FM lane, assembly measures the chip frequency contour to choose
between a compact F0 ramp and E7 tied pitch steps. A modulated bend uses E7.
F0 at the attack loads the normal vibrato counters. FA pauses vibrato at the
first moving pitch step while E7 changes pitch without keying on. When there
is a stable landing, F0 on its tie re-enables the paused counters and phase.
A slide through key-off leaves vibrato paused until the next affected attack.
`Sonic3kCoordFlagHandler` writes pending F0 parameters on a tie, but only
an attack initializes the live counters for a new vibrato shape.

A single F0 ramp uses a signed 8-bit F-number delta and stays in one octave
block without wrapping its counters. E7 can cross blocks with a chain of
FM pitches from MIDI 12 to 106 plus signed 8-bit detune. The artifact accepts
signed offsets up to 9,400 cents, the distance between those two endpoints.
Actual headroom depends on the starting pitch and available ticks. The
encoder skips contours outside its measured error budget or the native bank
capacity. Automatic stem tracking still uses a narrower plausibility window.
The report records applied bends under `bends`, with the
`stem-backed-pitch-bend` reason, encoding, gate, and contour error in cents.
A `bendSizeLimit` flag indicates a retry without the bend commands when they
exceed the project or bank capacity.

### Roles

`reconstruct plan-template WORK_DIR` prints template JSON to stdout.
Pass `--out FILE` to save it to a new file.

`roles.json` contains `harmonyMaxHz` and a `notes` array with
`{note, role, reason?, assignmentReason?, mergedInto?}` per corrected note. Valid roles
are `melody`, `counter`, `bass`, `harmony`, `drums`, and `drop`.
A drop requires a reason.

Monophonic MIDI parts stay intact as melody candidates. For each polyphonic
part, a dynamic program selects an attack path across bars. It rewards
played gate duration up to 240 ms and adds 45 ms of equivalent weight for
each attack. Attacks within 24 ms compete for one place in the path.
Local register, velocity, note confidence and stem motion weight each note.
Transitions charge for pitch leaps, gaps and overlapping releases. A handoff
can cut a sustained note, with credit limited to its played gate, so a
sequence of reattacks can outrank a long competing tone.

With no explicit lead, the heuristic compares part contours by bar and can
transfer melody labels between matched repeat instances. On staged
`reconstruct run` or `transcribe`, `--lead-part PART` reserves FM1 for the
chosen non-drum MIDI part, including through its rests. Scoped `melody` rules
in `roleplan.json` override the heuristic or CLI lead within their section,
bar and time bounds. Pitch and texture filters constrain the notes available
to the rule's tracker. The remaining parts are still available for backing;
a stem mapping supplies audio evidence only.

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
bonus. For a polyphonic part with a mapped stem, the upper-voice tracker
can revisit chord conflicts in the initial contour. A competing note needs
a clear velocity accent over the selected tone and independent energy in
the stem. It checks the fundamental and can use unshared overtones for a
note with strong vibrato. A pickup that overlaps the next sustained attack
of comparable velocity is excluded. The second path search considers the
remaining candidates alongside the initial contour and rewards the portion
of each supported hold that can play before another lead attack cuts it.

For audio-only synth, keyboard, piano, guitar and lead stems, a second pass
looks for an upper phrase inside the decoded chords. It requires a fresh,
salient stem attack, with a higher threshold when lower tones attack at the
same time. A lower note's harmonic must show a separate onset before it can
support an upper note. Nearby notes with small pitch steps support a phrase.
The pass can put the phrase on the primary melody lane or mark it as a
protected counter line when another part carries the melody. Trace shows
`stem-upper-line` for the promoted notes. The pass runs after the edit
journal resolves its selectors and preserves authored roles and pins.
A custom role plan retains control of the contour.

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
It requires the earlier or following pitch's fundamental in at least
70% of the gap's analysis frames, at a substantial fraction of its level
inside the corresponding note. A legato lead over chords dips briefly
where its partials beat against chord tones, so not every frame has to
pass. Evidence is excluded only when a notated unison tone sustains
through the whole gap. Octave and other harmonic relations do not veto
it: merged Suno chords usually contain the lead's pitch class, and a
doubled chord tone across a short gap is a smaller fault than a hole in
the melody.
For gaps shorter than the spectrum window, it also checks samples
strictly inside the gap so a same-pitch reattack cannot supply all the
evidence. Motion-based releases retain priority. Legato only changes the
earlier note's end and records `lead-legato` in the roles and continuity
counts. With stems, `stages.fm1InterNoteGaps` reports the counts of each
FM1 gap length in arrangement ticks, including zero-length gaps,
before and after legato on the allocated lane.

Rapid re-attacks on close, similarly accented notes of the same inner
pitch can merge into the earlier note when the source part carries
overlapping voices. Independent MIDI lines keep their repeated attacks,
even when notes from other parts surround their pitch. The earlier note extends its
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
`{note, role, outcome, lane?, startTick?, endTick?, reason?, mergedInto?,
competitorNoteId?, blockingNoteIds?, relocatedFrom?}`.
`outcome` is `kept`, `shortened`, or `dropped`. Shortenings and drops
require a reason. Kept and shortened entries have a lane and a nonoverlapping,
half-open presentation interval. The interval in the placement is
authoritative for assembly. Drops carry neither lane nor interval.

| Role | Hardware |
| --- | --- |
| Melody | FM1 for the whole song |
| Bass | FM2 for the whole song |
| Counter and harmony | FM3–FM5 first, then PSG1–PSG3 for eligible pitches |
| Drums | DAC for kick, snare and other primary hits. PSG3 for explicitly labelled noise parts and cymbals that collide with DAC hits or are quiet in the drum stem. |

The shared project format exposes five FM tone lanes, three PSG lanes
and DAC. The sixth FM channel and DAC share hardware. The arrangement
compiler exposes that slot as DAC. `--fm-only` disables PSG placement
while keeping DAC. Release collisions, unavailable lanes and simultaneous
DAC hits appear in the allocation and the final report.

Kick takes precedence over snare for simultaneous DAC hits. Both take
precedence over cymbals, and their first four ticks are protected from
lower-priority DAC retriggers. An eligible cymbal that loses the DAC can use
a short PSG3 white-noise hit. When a dedicated drum stem supplies calibrated
levels, a cymbal class within the quiet PSG range can use noise even when the
DAC is free. A louder measured class keeps its DAC allocation and collision
priority to preserve the stem's stronger attacks. When a kit has only loud
measurements, its unmeasured classes keep DAC priority as well.
Harmony spill avoids those PSG3 intervals. Counter lines, sustained
harmony with no other playable lane, and explicit lane pins can take precedence. The allocation records `psg-cymbal-dac-collision`
or `psg-cymbal-quiet-stem` for a substitute and `psg-noise-lane-busy` when
the substitute cannot play.

When a stem-backed upper line is promoted, it can take a lane from chord
backing. Bass stays on its own allocation path. Among chord tones, the
allocator prefers the root when bass does not supply it, the third and the
upper chord pitch ahead of octave doublings. A displaced backing gate is
reported as `yielded-to-upper-line`.

After audio-only allocation, a second pass checks the full requested gate
against the lane schedule. It prioritizes lead and bass, then stem-backed
holds, and can borrow idle FM1 or FM2 intervals. It restores shortened
gates intact when another lane is free or their backing blockers can
all move intact. In a release collision, an unshaped backing attack
with substantially weaker stem evidence can start up to two ticks later.
Notes with an explicit pin and authored chord voices
retain their allocation rules. Dropped backing notes covered by a same-part
unison near the same onset are reported as `covered-source-unison`. Gates
under three ticks are reported as `too-short-for-overflow` if they lost
the first allocation pass.

### Voice

Fresh `voicing.json` stores tonal profiles in `sourceInstruments`, keyed by
the instrument ID from `voices.json`. Each profile has one FM variant,
linear gain, articulation, and effects under `fm`. Every allocated FM lane
uses the same profile. The `shortlist` lists up to 18 candidate IDs, their
pitch-aligned attack match scores (higher is better), and their contextual
fit and held-out errors (lower is better). For a part that spills to PSG,
`psgVariantId` and `psgVolumeOffset` approximate that FM patch's attack,
sustain, release, and loudness using the catalogue's PSG envelopes and
hardware level steps. Automatic matching rejects muted attacks and mute pulses
and keeps the quietest spill audible for its first three frames or its full
gate if shorter. For otherwise
identical simultaneous harmony or counter attacks, allocation offers FM to the
longer note first. In stem-backed runs, allocation omits a repeat-consensus
backing note when an allocated note of the same part and pitch already covers
its full gate.
A fresh written attack still bounds a lead's inferred legato extension when
it carries a backing role.

For long notes with a mapped stem, the fitter measures pitch-band energy
near the attack and in the hold. It screens the FM attack shortlist on
representative long gates using the pinned chip. For shared FM guitar
profiles, the hold screen compares each stock patch with the stem.
On S3K, exposed attacks with a mapped stem also compare time to near-peak
level in 5 ms bins and energy above 2 and 5 kHz. Guitar profiles use a narrower
acceptance margin. It fits the surviving stock choices jointly with the
other instruments. The PSG spill fit includes a long-hold probe alongside
its short FM contour probes. If an attack would
steal most of a stem-supported hold from another source part, allocation
first tries moving the whole hold to a free inner FM lane. It can also route
the new attack around the hold when another compatible lane is free.

For isolated S3K source parts, the stem label or instrument role adds a
bounded preference to the stock FM shortlist and joint training and
held-out loss. To enter the joint fit, a candidate's chip probes must stay
audible through every stem-supported hold that survives FM allocation,
using the allocated pitch and project-time gate. A steady probe can satisfy
a growing source note. If the acoustic shortlist has no sustaining patch,
the fitter searches the whole stock catalogue. If every candidate fails,
it keeps the patch with the smallest total sustain shortfall and records a
`held-note fallback` in its selection reason.

A name match adds up to 0.025 and a known mismatch subtracts
0.0125. A retail register and role match adjusts the score by at most 0.015
in either direction. The loss uses the same adjustments proportionally.
The priors are fixed across songs. The acoustic fit can overcome them.
The name categories are:

| Source class | Compatible community name terms |
| --- | --- |
| Bass | Bass |
| Guitar | Picked, Slap, Strike, Harpsichord |
| Keyboard or piano | Piano, Harpsichord, Organ |
| Synth | Synth, Metallic |
| Vocals or lead | Lead, Flute, Bassoon, Trumpet |
| Strings | Harpsichord, Picked |
| Brass | Brass, Trumpet, Horn |
| Organ | Organ |
| Bells | Bell, Xylophone |
| Drums | Wood Block is the FM percussion label. Drum fitting continues to use its DAC and PSG path. |

Unclassified bass roles can use the Bass name category. Retail use compares
the register of the source part with observed MIDI pitch ranges and medians.
Melody and counter roles look for lead use, harmony for chord use and bass
for bass use. Short median retail gates reduce support for sustained parts.
Rescan a retail ROM with `catalog scan` to add usage observations to an index.
Old indices still use community names. The automatic screen rejects Silent
Bass, near-silent probes and one-shots on sustained parts. Explicit variant
choices can still use these voices.

Read the selected voice and evidence under `patchReason` in `voicing.json`
or in the assembly report's matches. `reconstruct describe WORK_DIR --json`
and MCP `reconstruct_describe` include `stockVoiceChoices`. In Studio, select
an FM note and read **Automatic FM fit** in Detail.

The `lanes` map retains DAC and PSG3 drum settings and explicit lane
overrides. New tonal lanes start with unity gain and inherit their source
profile. A lane-wide FM `variantId`, a PSG part entry in `lanes.*.instruments`,
or a scoped patch override can replace the default. An explicit voice
assignment takes priority over these patch choices. Existing lane-only
files can still be assembled. Back up or move one aside before refitting
to adopt the shared defaults. DAC `instruments` entries also specify
`drumPitch`. A fitted DAC entry also has a `drumSelection` with the chosen
rate, optional stem slice and match evidence. With a dedicated stem, the
matcher prefers isolated hits, cuts windows at subsequent strong onsets,
and takes the median distance within each MIDI drum class. A window cut
while the hit is still audible provides a lower bound on its decay. It compares spectrum, attack, decay, and low resonance
for kicks and toms. For hats, cymbals, sticks and claps it analyzes the same
upper bands in the stem and candidate, and charges for excess low body in
the DAC playback. It tests native DAC rate bytes around each catalogue
recipe within a six-semitone search. Without a mapped drum stem it uses the
mix fit and a smaller rate search.

The fitter profiles the decoded PCM at each tested rate. Speech-like and
pitched/tonal samples are excluded from automatic drum fitting, including
the legacy mix vote. A speech-like or tonal classification at any native
catalogue rate excludes the asset across its rate variants. Hats and
cymbals require a noise-like profile at the fitted rate. Named classes,
played native kick and snare slots, and declared slots provide soft priors
among eligible candidates. When several candidates share recorded DAC use
in a catalogue song or belong to an authored bank, the fitter gives that
kit a small bonus. Reusing the same PCM across classes costs more.

The `drumSelection` evidence records the requested drum class, acoustic
profile version, representative excluded rates and reasons, and the
spectral fit and preferred kit for eligible candidates. When no DAC
candidate qualifies, the catalogue fit uses the PSG3 noise envelope where
the lane can accommodate the attack. Existing PSG3 notes have priority.
Assembly records the actual PSG3 gate or a collision in the report. The
FM-only option disables the fallback. In `auto` source mode, a candidate
stem slice must pass the same eligibility check. The explicit `sliced` mode
uses the chosen stem as its source. To deliberately use a DAC variant at its native rate,
set `variantId` and remove `drumSelection` from the DAC instrument entry.
This also works for an asset marked `excludeFromDrumFit` in the catalogue.

Automatic PSG3 cymbals use tone-linked white noise. Closed and pedal hats
start at 13.98 kHz for one driver tick (about 16.7 ms) and switch to 6.99 kHz
for the decay. The envelope uses attenuation offsets 0, 4 and 9 before rest.
The clock change holds the note so the envelope continues without resetting
the noise generator. Open hats and other cymbals use the 6.99 kHz clock and
a longer decay.

For closed and pedal hats, the voice stage weights the drum stem toward 6 kHz
and above, subtracts pre-hit bleed, and targets 6 dB above the median isolated
attack excess against a fixed 13.98 kHz calibration signal. Open hats and
other cymbals use the broader upper-band fit. The fitted correction is saved
in `drumGains`. The closed-hat voicing adds one hardware step (about 2 dB)
to its base level, taking the audition's level change from +4 to +6 dB.
Without enough stem evidence the substitute uses its base level.

The fit renders the allocated phrases through the pinned S3K chips over up to
eight excerpts spread across the song. It models the reference magnitude
spectrum as a sum of instrument spectra and uses constrained least squares
to estimate instrument gains. Each candidate plays the part's allocated
notes in the fit excerpts. When a source instrument carries an established
melody and backing, its line drives the shared patch, gain and articulation.
Excerpt selection weights sustained, higher-velocity line notes more heavily
and discounts ticks covered by that source instrument's other notes. The
attack shortlist also favors exposed, sustained onsets and weighs stronger
audio probes more heavily. If none of the candidates explains enough of the
line’s attack spectrum, the fit uses the whole part as evidence. The same
profile plays the backing voices.
Where stems are supplied, the stem objective compares the fit notes against
their mapped source. Separate excerpts validate patch changes. The final
level accounts for backing that will play at the same gain. A low-register instrument on one FM lane can
refine its patch using its mapped stem. This pass compares the first 16
harmonics and the low-frequency energy of notes played with their preceding
note context. A replacement must improve two sets of note excerpts.
Articulation and effect alternatives must also win on both partitions to
replace the role default. Insufficient evidence records
`default (inconclusive)`. Melody defaults to mild delayed vibrato and adds a
quiet echo when an FM lane is free. Harmony defaults to light detune. Bass
has no default pitch effect.

Edit `variantId` using a catalogue variant ID. `gain` is a linear amplitude
multiplier converted to native volume (0–4, subject to chip headroom).
The optional `partGains` object multiplies that gain for specified source
part IDs. Lane-only level calibration measures gated K-weighted stereo power
with the selected patches and MIDI velocities. A mapped bass stem is compared with the aligned mix
after subtracting that stem. Other independently mapped tonal groups can
be compared against the drum stem when the drum group has a dedicated
reference. Peak checks gate volume boosts.

A fresh voicing balances the rendered DAC and PSG3 percussion against the
rendered tonal parts when both groups have independent stem mappings. The
source target sums only the unique mapped stems of playable tonal parts.
The fit uses 400 ms stereo K-weighted blocks on a 100 ms hop, with the
BS.1770 absolute and relative gates to ignore quiet windows. It applies a
common kit correction when the balance differs by more than 1 LU, rounds
PSG3 noise to its 2 dB volume steps, and
checks the rendered result after scaling the DAC's unsigned 8-bit samples.
The shared tonal instrument gains and PSG spill settings stay intact.
Retained voicings keep their saved drum gains. Move a saved voicing aside to
fit a fresh kit balance.
`articulation.gate` is the fraction of the allocated duration held before
key-off. Fresh automatic fits use exact stock FM patches and compatible stock
PSG envelope programs. Gain is applied through channel volume commands.
Saved automatic `fast-release` settings replay with the stock patch. Use a
scoped release-rate edit to intentionally create an FM clone.
`effects.detune` is a signed frequency offset.
Optional `effects.vibrato` holds the four native F0 parameters `delay`,
`rate`, `delta` and `steps`. In [S3K's driver](../../openggf-audio/src/main/java/com/openggf/audio/smps/SmpsSequencer.java),
`delay` counts ticks until the rate counter starts. `rate` counts ticks per
accumulator update, and signed `delta` is a native packed-frequency increment
per update. `steps` counts sustain ticks per direction, starting at
`floor(steps / 2)` on attack. The sign reverses every `steps` ticks
thereafter. At S3K reconstruction tempo zero each tick is 1/60 s. When `rate`
divides `steps`, a full cycle is `2 × steps` ticks and the peak-to-peak
accumulator excursion is `abs(delta) × steps / rate` native units.
The cents change depends on the pitch. For example, `rate: 2, steps: 12`
cycles every 24 ticks (2.5 Hz).
Optional `effects.copy` gives an unused FM
`lane`, `delay` in ticks, relative `gain`, signed `detune`, and optional
`vibrato`. Preset names and decision reasons are descriptive. The numeric
settings control assembly.

On `DAC` and `PSG3`, `drumGains` maps MIDI drum pitches to linear gains
from 0 to 4. For example, `"drumGains":{"42":0.1}` attenuates closed hi-hats.
Missing pitches use unity gain. DAC scales the unsigned PCM around its
midpoint. PSG3 applies gain at the nearest hardware attenuation step of
about 2 dB, with 0 selecting mute. Drum gains persist through refits in
the same work directory. On DAC, `drumGainOverrides` replaces that gain
for specific hits. Each entry takes `hitIds` or a half-open `span` of
effective onsets in source seconds, and an optional MIDI `pitch` filter.
The last matching entry wins. For example, set the `drumGainOverrides`
field on `lanes.DAC` to this array.

```json
[
  {"hitIds": ["KICK_ID"], "gain": 0.75},
  {"span": [12.5, 13.0], "pitch": 38, "gain": 0.5}
]
```

#### Inspect lane policy decisions

Run `reconstruct assemble WORK_DIR --policy-decisions` (or pass the flag to
`reconstruct run`, or MCP `options.policyDecisions: true`) to include
`policyDecisions` in `report.json`. Each entry identifies the source note,
policy, target lane when available, attack tick, gate and delay in ticks,
and applied or skipped status. Busy skips list the competing note and
occupied interval on each candidate lane. `reconstruct trace` reads the
decisions from the current assembly.

A note-specific layer with `"mode":"off","policies":["fmDouble"]` masks only
the FM policy, leaving the PSG double eligible. Name any combination of
`pads`, `leadEcho`, `psgDouble`, and `fmDouble`. Omit `policies` to disable
all layers and lane policies for the selected notes.

#### Tune or override PSG3 hats

Colliding cymbals and cymbal classes measured quiet in a dedicated drum stem
can use PSG3 automatically. Kick and snare retain DAC priority. Review the
default placement and its reason with `reconstruct trace WORK_DIR --lane PSG3`.
The drum rows in the view and `trace --lane DAC --json` show remaining
simultaneous DAC losses and PSG3 availability. Use `trace --lane DAC`
or `trace --lane PSG3` for `drums.droppedHits`. Its competitors include
the busy interval and a `pinLane` suggestion for each compatible lane
that is free over the competing tone's full gate. Dry-run the chosen pin
and rebuild from roles to confirm the revised allocation.

- **Tune automatic hats.** Set a pitch gain in `lanes.PSG3.drumGains` (for
  example `{"42":2}`) or edit the clock fields in `lanes.PSG3.psgPercussion`.
  Assemble and audition with `render-project --solo PSG3`.
  This replaces the fitted gain for that pitch. Check the trace for tonal
  notes on PSG3 in the same passage when listening to the solo.
- **Override routing.** Pin a hat to DAC to request the sampled hit. To
  force PSG3, choose a passage with room for the full authored gate and pin
  any conflicting tones to verified free tonal lanes before pinning the hat.
  A PSG3 pin uses the authored gate and fixed-level noise. Use
  `reconstruct edit WORK_DIR add --file BATCH` with a batch such as:

  ```json
  [
    {"id":"keep-tone","op":"pinLane","reason":"Reserve PSG3 for hats",
     "noteIds":["TONE_ID"],"lane":"PSG2"},
    {"id":"move-hat","op":"pinLane","reason":"Use a fixed PSG3 noise gate",
     "noteIds":["HAT_ID"],"lane":"PSG3"}
  ]
  ```

  Run `reconstruct run --from roles WORK_DIR --keep-voicing`. Check
  `edit list` for blocked pins and trace the resulting lane and gate before
  listening. Apply scoped DAC gains only to hits still on DAC.

Read `lanes.PSG3.psgPercussion` for the noise settings. Byte 231 (0xE7)
selects white noise clocked by tone 3. `envelopeSlot: 0` is the fixed-level
mode used for pinned and explicitly named PSG percussion. Automatic
cymbals use the short or long decay envelope assigned in the arrangement.
The three clock fields apply to automatic closed and pedal hats.
`hatAttackPeriod` defaults to 8 (13.98 kHz) and `hatDecayPeriod` defaults to
16 (6.99 kHz). Each takes a tone-3 period from 1 to 143. The NTSC noise shift
rate is approximately 111.86 kHz divided by the period. `hatSwitchTick` is the
offset from the hit in driver ticks, either 1 (default) or 2. Setting equal
periods gives one constant rate. Older voicing files use the defaults for
missing fields. The generated sequence uses detune to set the periods and a
hold at the switch when the hit lasts that long.

The existing PSG velocity curve starts with an FM-style logarithmic
velocity conversion and then quantizes the resulting 7-bit level to the
PSG's 4-bit level. A velocity scale of 0.32 typically moves just one or two
hardware steps, about 2–4 dB. Use `drumGains` for larger percussion level
changes. DAC sample amplitude is independent of MIDI velocity.

Optional `overrides` entries change an allocated tonal lane within a passage.
An entry may supply `startTick` and `endTick` or `startSeconds` and
`endSeconds`. Seconds round to the nearest playable tick on the project's
target clock. Ranges include their start and exclude their end. An entry
can also supply `noteIds` from `allocation.json`. IDs alone select whole
notes. Combining IDs and a time range selects their intersection.

```json
{"startSeconds":45.3,"endSeconds":48.8,"variantId":"variant-…","gain":1.1}
```

Each entry must set at least one of `variantId`, `gain`, `gate`,
`minimumReleaseRate`, `vibrato`, or `modulationOff: true`. Gain replaces the
lane gain before applying per-part gains. FM selects a stock voice. An explicit
release-rate edit that changes its operator bytes creates a custom clone for
that scope with `origin.clonedFrom` set to the stock asset ID. A deliberate
`chordInstrument` operator edit also creates a clone. Tonal PSG can select a
compatible stock PSG volume envelope.
Vibrato takes the same F0 arguments as `effects.vibrato`. Overlapping entries
compose when they set different fields. For example, a patch override can
cover the whole phrase while a note-level vibrato override affects two
attacks. Overlaps that set the same field, invalid variant kinds, and stale
note IDs fail validation. `vibrato` and `modulationOff: true` control the
same field.
The gate is selected at attack. Patch, level and modulation can switch while
a note is held, then revert at the range end, including during a release
that reaches that boundary. Note-only overrides give the selected note its
release before restoring settings for the next attack. Loading a new vibrato
shape mid-note requires a reattack.
Bends keep priority over custom vibrato during the moving segment. Dedicated
copy lanes keep their original settings. Assembly deduplicates FM patches by
their native bytes, including changes to release rates, with up to 128 local
voices and a 32 KiB total bank.

#### Optional lane policies

New reconstructions save the target's layering preset in `voicing.json`.
The `s1-layering`, `s2-layering` and `s3k-layering` presets add an octave-up
PSG double and a 14-tick FM echo on spare channels. FM-only runs retain the FM echo. Existing work and
replays reuse their saved policy. Set `--lane-policy-preset off` on a fresh
run, or rebuild an existing work directory with a replacement preset:

```sh
java -jar "$jar" reconstruct assemble WORK_DIR --lane-policy-preset s3k-layering
```

In MCP, call `reconstruct_stage` with `stage: "assemble"` and
`options: {"lanePolicyPreset": "s3k-layering"}`. In Studio's reconstruction
editor, choose the target’s preset under **Lane policies**. These actions replace any
previous lane policy configuration. Use `off` or choose **Off** to clear it.

The preset FM echo adds 11 carrier total-level (TL) steps relative to the
dry attack (8.25 dB nominal, subject to the patch's range). This is 4 steps
or 3 dB quieter than the original 7-TL echo.
It uses the patch's own envelope throughout the echo. To choose a different
level, set the optional `echoAttenuation` field in `voicing.json` and run
`reconstruct assemble WORK_DIR` to reuse the saved preset.

```json
"lanePolicies": {"preset": "s3k-layering", "echoAttenuation": 15}
```

`echoAttenuation` is the total additional carrier TL relative to the dry note
(0–127 integer steps, 0.75 dB per step). The example is 3 dB quieter than the
preset default. The delay stays at 14 frames.

For custom settings, set `lanePolicies` to an array of rules. Each rule
applies to the whole song by default. Use `section` for a section ID or unique label, or `span` for
`[startTick, endTick]` in arrangement ticks. Use `voice` with an ID from
`voices.json` to narrow the selection further. Selection uses the dry note's
attack on a half-open interval. Its gate and delayed copies can extend past
that interval up to the project end.

```json
"lanePolicies": [
  {"policy":"pads","voice":"part-0-chords"},
  {"policy":"leadEcho","span":[2400,3600],"delayTicks":7,"octave":0},
  {"policy":"psgDouble","voice":"part-1-chords","octave":1},
  {"policy":"fmDouble","mode":"chorus","section":"A"},
  {"policy":"fmDouble","mode":"echo","delayTicks":14,"attenuation":7,
   "volumeSteps":[{"tick":6,"attenuation":12}]}
]
```

| Policy | Source and defaults |
| --- | --- |
| `pads` | Harmony, or counter material in a named voice, with an allocated gate of at least `minimumTicks` (18 by default). Move it onto PSG with one sustaining envelope. Keep the dry placement when the move fails. An unpinned counter already on PSG can reclaim a freed FM interval when its shared FM instrument is available. |
| `leadEcho` | FM melody with a decaying PSG envelope, delayed seven ticks by default. |
| `psgDouble` | FM harmony, counter or a solo FM tone with a sustaining PSG layer at the same attack time. |
| `fmDouble` | FM melody by default, or notes in an explicitly scoped FM voice. Chorus has the same timing, −2 detune units and one extra TL step, with custom vibrato omitted for notes without a rendered bend. Echo also accepts solo FM tones and follows the source's attack vibrato with an eight-tick delay, +2 detune units and seven extra TL steps. |

The three PSG policies accept `octave` (−2 to +2, default unison) and
`levelDb` (−36 to 0, defaults −6 for pads, −12 for lead echo and −10 for
doubling). Levels compare K-weighted solo probes against the dry note and
quantize to audible PSG steps. Short probes use the K-weighted mean power.
FM `detune` is relative to the source, in signed S3K driver units.
`attenuation` is additional carrier TL (0–127, about 0.75 dB per step within
the patch's available range). An FM echo can set new relative attenuation
at increasing `volumeSteps[].tick` offsets from its own attack. Both echoes
accept `delayTicks` in the range 1–600.

For PSG doubling and FM echo, a solo tone has no other dry tonal gate
overlapping its voiced interval. A solo attack tagged bass qualifies at
MIDI pitch 48 or higher. The rule still honors any voice, section or span
scope. This lets a mid-register arpeggio carry the effects through a sparse
passage even when its role changes. Drums do not count as tonal gates.

Assembly protects existing gates, including future drum gates on PSG3.
Copies use a free lane for the complete voiced interval. FM and PSG copy
policies follow rendered bends using the same pitch transfer as per-note
layers. Pads keep a bent dry gate on FM. One-channel chord figures keep
their existing encoding. Pads preserve
authored lane, instrument and scoped voicing decisions. Pads run first,
then lead echo, PSG doubling and FM doubling. The first matching rule of
each kind or FM mode owns the dry attack.

`report.json.lanePolicies` records eligible and applied attacks, busy and
incompatible skips, destination counts and pad relocations. For eligible
bent notes, `bendLayers` records the destination and curve error, or a
skip reason. Byte deltas
compare the compiled arrangement with the indexed rule omitted while
keeping the other rules enabled. The report gives bank bytes and PSG envelope asset bytes. An omitted or empty array leaves
assembly behavior unchanged.

### Per-note vibrato envelopes

An S3K `noteVoicing` edit can replace the allocated FM note's vibrato with a
series of stages. These are project driver ticks relative to its allocated
attack, even when the source and project timelines differ.

```json
{"id":"lead-vibrato","reason":"Match the held lead's widening vibrato","op":"noteVoicing","noteIds":["LEAD_ID"],"vibratoEnvelope":[{"startOffset":0,"depthCents":0},{"startOffset":18,"rateHz":5,"depthCents":20},{"startOffset":32,"rateHz":5,"depthCents":48}]}
```

Offsets are strictly increasing integers within the allocated note. Depth
is half the peak-to-peak excursion, from 0 to 200 cents. Use 0.12–15 Hz for
moving segments. A zero-depth segment can omit `rateHz`. The opening stays
flat when the first offset is greater than zero. `vibratoEnvelope`,
`vibrato` (raw F0 fields), and `modulationOff` are mutually exclusive.
The envelope applies to one held attack at a time and supersedes inferred
bends and scoped vibrato on that attack. Authored bends, chord figures, PSG,
and S1/S2 targets are rejected. The normal articulation gate is extended
only if needed to reach an authored boundary within the allocation.

The compiler prepares the first moving stage with F0 on the original attack
when its onset fits the driver's delay range. Reissuing F0 under E7 does
not reload the live modulation delta. Later stages therefore stop custom
modulation and use native detune and pitch updates under E7, including
for onset offsets outside the F0 delay range. These continuations retain
one FM key-on. The first stage's Hz and depth are quantized to native F0
values. Later stages are sampled at 60 Hz and consume bytes in the music
bank.

`reconstruct vibrato-review WORK_DIR [--part ID] [--propose]` examines the
mapped stems of allocated FM notes. It estimates rate and depth from
consecutive alternating pitch excursions. It looks for a quiet opening
before sustained oscillation and for sustained changes in cycle depth or
period. The command prints candidates and the number of examined notes.
With `--propose`, it saves candidates for review in the `staged-vibrato`
group. Accept a proposal and rebuild assembly to hear it. Trace and describe
show the stages on the rebuilt gate. The default reconstruction does not
run this review or change its output.

Run `reconstruct run --from assemble WORK_DIR` after editing voicing.
`run --from voice` also reuses existing voicing unless refit options are given,
or generates it if missing. Use `reconstruct voice WORK_DIR` to explicitly
refit the current allocation. A refit preserves overrides, lane gain and
part gains by default. A rebuild from upstream recomputes levels for a lane when its
allocated notes, gates, velocities or bends change. Role labels alone do
not trigger releveling. Pass `--lane LANE` repeatedly to restrict which lanes
can change, and `--refit-levels` to recompute their levels. Without a lane
filter, an explicit refit considers every lane. The CLI reports changed
variants, gain and part gains for standalone and chained runs. MCP returns
them in `refitChanges`. The fit starts from still-available FM variants and
favors incumbents on close scores.

Use `--keep-voicing` on `run --from` through voice or on `rerun` to reuse
compatible lane settings. The run reports stale part gains and instrument
entries as it drops them. It fits any newly needed lanes or instruments and
reports a copy effect if its destination is no longer free. It preserves
scoped overrides on retained lanes for validation. The flag is separate from
`--lane` and `--refit-levels`. MCP resumed runs accept `options.keepVoicing`.

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
`fmPatchSwitchBytes` counts two bytes for each explicit FM patch selection
inside a compiled clip, including scoped changes and repeat occurrences.
Initial clip voice selections are part of clip reset and excluded.
`fmPatchBankBytes` counts 25 bytes per FM patch installed in the bank.
`tempoCurve` includes the start and end BPM when known, the maximum sustained
fractional deviation from the opening tempo, confidence, whether a warp
was applied, the steady BPM when applicable, and the presentation-rounded
duration in project seconds. `stages.phraseGroups` counts tolerant repeated
phrases and `stages.repeatGroups` counts exact bar candidates for clip reuse.

Warped runs also write `reference-steady.wav` and attach it at offset zero
in the Studio arrangement. The stereo stretch uses cross-correlated
overlap-add to preserve pitch and applies the inverse project-to-source map.
The CLI `render-compare WORK_DIR --start S --end S OUT.wav` maps source
seconds to the assembled project clock. It uses `render.wav` and the original
mix or saved steady reference by default. Use `--time project` for project
bounds. Results report both clocks and project ticks. `--render WAV` selects
a different project-time render and `--reference WAV` selects an aligned source recording
or the saved steady WAV. Choose `.mp3` for ffmpeg encoding. Both formats contain
the render followed by the level-matched reference, with the split point in
the JSON result. Use `render-project --start S --end S --solo LANES --mute LANES`
for an excerpt from a project in project seconds. Lane lists are comma-separated.
Both CLI commands accept `--force` to replace the destination by renaming a
completed sibling export.

With MCP `render_compare`, provide the work directory, its `render.wav`, and the
original mix or the steady reference. MCP substitutes the steady file when
the original mix matches the assembly report. A/B excerpt ranges use project
seconds with `time: "project"`; they default to source seconds. Use
`referenceOffset` for an independently aligned project-clock reference.
Source mix and stem excerpts for voice fitting always use source seconds.
`effectCopyLanes` identifies dedicated FM copy lanes that actually reached
assembly. If copies would make the bank exceed the native size limit,
assembly retries without them and records `echoBankLimit`.
Repeat clips for a lane share one sequence ID when the relative note timing,
pitch, articulation and instrument commands agree. Releases crossing a
bar edge keep their continuity. The package compiler bakes source reuse
into the playback bank.

`report.json` records `heldNotes` when the source contains measurable holds.
It compares pitch-band stem energy with the assembled gates and solo lane
energy around the tail. `held-but-silent` includes tails whose measured
decay materially exceeds the source. `truncated` and `missing` identify
notes without an assembled gate at the stem-supported tail. Trace attaches
the evidence to the note ID and describe lists findings in the selected
part and span. `reconstruct proposals list WORK_DIR --group held-notes`
shows review suggestions under `heldNoteReviews`. These suggestions use
the assembled output and do not make automatic edits.

### Inspect external evidence

`reconstruct view --notes FILE` overlays source-time MIDI or JSON note triples
without changing transcription. Repeat `--harmonics-at PITCH[@TIME]` for
pitch probes independent of trace note IDs. `--ridges` writes a sibling
`.ridges.json` with up to three interpolated peaks per time column for
the source and, with `--render`, the chip panel. `--ridge-count N` enables
the export and sets the count to 1–32. With `--harmonics NOTE_ID` or
`--harmonics-at PITCH[@TIME]`, it includes a trajectory for the fundamental
and the two linked partials at twice and three times its frequency. Each
point has both clocks and each measured peak has its own spectral dBFS.
`--time project` (MCP `time: "project"`) sets the clock for bounds and
harmonic probe times. The view legend gives both spans.
`--db` plots the selected fundamental's level under each panel and returns
the numerical tracks in the legend. `--shared-db` uses a common color
scale. The view also draws authored bends and assembled FM pitch curves.
`--pitch-preset drums` sets MIDI 35–81. `--force` replaces the PNG and
requested sidecar from completed staged files. MCP `reconstruct_view`
supports `notes`, `harmonicsAt`, `ridges`, `ridgeCount`, `db`,
`sharedDb`, and `pitchPreset`.

### Audition a frozen phrase

`reconstruct audition WORK_DIR --lane FM1 --start S --end S --variant ID OUT.wav`
changes only the specified lane in a temporary overlay of the assembled
project. Bounds default to source seconds; use `--time project` for project
seconds. Use `--note TRACE_ID` instead of a range to audition the note's
assembled voiced gate. Its FM lane is inferred unless supplied. Audition
keeps timing, other lanes, copy tracks and authored project edits.
The overlay swaps FM patches on the exact rounded project interval and
preserves a held note's envelope state. Use a range starting before a
relevant attack to compare how each patch develops through the hold.
The default is in-mix playback. Use `--solo` to hear just the target lane,
or `--gain` to multiply its existing level within the phrase. Each candidate
plays from song start, discarding the warm-up PCM before the requested range.

Repeat `--variant` for up to 32 candidates, or pass `--shortlist` to use
the saved shortlist on the explicit or inferred lane. A batch writes
numbered files and reports up to
seven successive RMS bins. `--reference WAV` appends the aligned reference phrase after
matching the two RMS levels by attenuation. If the report identifies the
original reference for a warped run, the tool uses the saved steady WAV.
Audition leaves the artifacts untouched. Add an override and assemble to
publish it in a passage, or run `reconstruct voice WORK_DIR --lane FM1 --variant ID`
to fit a specific lane-wide FM patch and its level in one step, then assemble.
`catalog show INDEX.json ID --brief` gives occurrence counts and keeps the patch
and variant detail. MCP `reconstruct_audition` takes a `variants` array
and returns the candidate paths, variant IDs and pre-match RMS envelopes in
linear units, with source and project bounds and ticks. MCP uses `note` and
`time` for note ID and clock. Save the same fields with `--rms-json FILE` or
MCP `rmsJson`.

Assembly probes the lane-wide FM1 patch in isolation on up to three of its
longest allocated gates of at least one second, including with
`--keep-voicing`. `sustainProbe` gives the sample count, number of gates
longer than half a second, and worst measured dip when available. A dip
greater than 6 dB adds `voicingWarnings` and identifies the flattest audible
shortlist patch on that gate. The standalone `voice` stage also records its
warning in `voicing.json`. The probe is diagnostic only.

## Benchmark

`bench run` invokes the staged chain in process. The benchmark excludes
report-flagged effect copies from note events and measures the complete audio
render. The `suno-like` profile adds musical-grid and repeat measurements.
The runner also retains
`stage-reductions.json` with reason counts per case. Use the
[benchmark guide](benchmark.md) for the corpus, holdout and gold commands.
The legacy positional `reconstruct MIDI WAV INDEX OUTPUT_DIR` command
continues to use the original allocation policy for comparisons.

### Preserving note edits when classification changes

Note IDs identify attacks independently of their role, voice and chip lane.
A rebuild from roles, voices, allocation or voicing retains the score edits
and re-evaluates note voicing and layers against the new allocation. Explicit
role, lane and voice membership edits follow both child IDs after a cut.
When a merge combines notes classified together by a single edit, that
classification follows the retained attack.

A score operation can succeed while its target fails to sound downstream.
Assembly records these losses as orphaned edit outcomes with the note ID and
the allocation or voicing reason. An incompatible instrument or vibrato
setting is reported per target. Other compatible notes in the same edit
still receive the setting. The original edit stays in the journal and can
apply again if the note regains a compatible gate.

`reconstruct describe --json` and `reconstruct_describe` include
`orphanedEdits` when present. The text description lists them after the
score. In Studio, use **Edits → Revise selected edit…** to change the
note IDs or selector, or remove the edit and apply the draft. Agents can
remove the entry and add the corrected intent.

Change the first stage as needed when moving a section boundary. A chained
run automatically goes back to transcription when the score edit recipe
changes, including the sections journal for a score edit that uses a section.
