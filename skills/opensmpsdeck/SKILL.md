---
name: opensmpsdeck
description: Reconstruct aligned Suno MIDI, mix WAV, and optional stems into an S3K SMPS arrangement with OpenSMPSDeck. Use to diagnose or edit stage artifacts, audition chip renders, address Studio review notes, or benchmark reconstruction changes.
---

# Reconstruct and review an S3K arrangement

Use the local OpenSMPSDeck checkout and its packaged headless JAR. Prefer the
`opensmpsdeck` MCP tools when configured. Otherwise run commands with
`java -jar "$jar"`, where `jar` is the absolute path to the headless JAR.

## Checklist

- [ ] For a Suno download, call `reconstruct_import` with absolute `folder`,
  `workDir` and your trusted `catalogue` path. Check `import.json` for
  file status, alignment and the evidence for each mapping. Re-check with
  `assign: {"stems/synth.wav": 0}` or `exclude: ["stems/fx.wav"]` if needed.
  Pass the returned `runRequest` to `reconstruct_run` when it has a
  catalogue, or use the printed CLI command. CLI equivalent:
  `reconstruct import FOLDER WORK_DIR --catalogue INDEX.json`, with repeatable
  `--assign FILE=PART`, `--exclude FILE`, and optional `--run`.
- [ ] For individually supplied files, identify the MIDI, mix WAV, optional stems and local instrument catalogue.
  Check that the MIDI and audio share time zero. Map stems to zero-based MIDI
  **part IDs** from an existing reconstruction report or the transcribed
  `score.json` (`source.parts`). Use a private work directory with an existing parent.
- [ ] Call `reconstruct_run` with `inputs: {midi, wav, catalogue, stems}` and a
  fresh `workDir`. Omit `stems` when unavailable. Start with
  `options: {seconds: 60}` for a first-minute review. CLI equivalent:

  ```sh
  java -jar "$jar" reconstruct run "$midi" "$wav" "$catalogue" "$work" \
    --seconds 60 --stem "0=$lead" --stem "1=$bass"
  ```

- [ ] Read `report.json` for source part IDs, reductions, patch shortlists,
  bank size, copy lanes and presentations. Inspect the artifact that owns the
  decision. MCP `artifact_read` accepts a `selection` of keys and array indexes
  as strings, for example `["lanes", "FM1"]` within `voicing`. Prefer focused
  reads for long tracks.
- [ ] Decide the musical roles before tuning sound. This is usually the
  largest quality lever. See **Plan lead and backing** below.
- [ ] Listen to the same time range in the S3K render and source. Use
  `render_clip` on `arrangement.smps.json` with `start`, `end` in project
  seconds and a new relative WAV filename in `output`. Use `solo: ["FM1"]`
  to isolate the lead or `mute: ["FM1"]` to hear the backing. Use
  `render_compare` with the full `render.wav`, reference WAV, `workDir`,
  and the same range to write a level-matched render-then-reference WAV.
  Apply the reference's offset if its time zero is shifted.
- [ ] When a human is reviewing the same work directory, save note repairs as
  proposals with `reconstruct_edit {workDir, propose: true, edit}`. Give each
  intent a stable `id` and `reason`. Use a shared `group` for a batch and
  optionally include `evidence: {text, path}` with a view or render path
  relative to the work directory. Let the human accept or reject in Studio.
  In your own work copy, use `action: "add", rerun: true` to apply directly.
- [ ] For proposals, use `reconstruct_run {workDir, withProposals: true, proposalId}`
  or pass `group` to audition the batch. Read the dry-run outcomes from
  `reconstruct_edit {workDir, action: "proposals"}`. For direct edits, inspect
  `traceDiff`. Validate the rebuilt project and compare the revised render
  with the previous clip and source.
- [ ] Reply after verifying the change. Mention the specific change, lane
  and time range, and the clip filename. Keep unresolved issues open.

## Trace a missing attack or changed gate

- Use `reconstruct_trace {workDir, start, end, part, pitch}` before editing a
  missing, misplaced, or clipped note. Times filter in source seconds.
  Add `lane` or `status` to narrow the result. The trace carries source and
  project seconds, placement ticks, role decisions, merge partners,
  allocation competitors, and the final voiced gate and bend. Compare
  original `timing` with `effectiveTiming` after edits.
- After a focused repair, compare separate baseline and candidate work
  directories with `reconstruct_trace_diff {oldWorkDir, newWorkDir, span}`.
  Use `span: "START,END"` in source seconds to flag changes outside the
  edited passage. Check `lanePermutations` for grouped swaps, the first
  affected note IDs, and suggested `pinLane` edits. Check `voicingChanges`
  for variant, level and scoped override changes. Check `compiledTimeline`
  for changed native events and `renderedPcm` for changed audio frames.
  When PCM differs after the span, check `renderedPcm.psgTail` for
  differing soloed PSG lanes and causes such as carried oscillator phase.
- Use `reconstruct rerun "$work" "$fresh" --search "$input_dir"` to replay
  the inputs and options of a historical fresh run. The private
  `manifest.json` pins input hashes and retains imported note snapshots.
  Replay carries `edits.json`, `roleplan.json`, clip anchors and lane
  affinity into the fresh directory. Add `--keep-voicing` to carry accepted
  voicing settings. Check stderr for invalid and orphaned edits and
  `allocationAffinityChanges` for lane choices that differ from a fresh
  allocation.

CLI examples:

```sh
java -jar "$jar" reconstruct trace "$work" --start 45.5 --end 46 --part 0 --pitch 68
java -jar "$jar" reconstruct trace-diff "$baseline" "$candidate" --span 45.5,46 --json
```

## Inspect attack timing and harmonics

When a score grid makes an arpeggio look like a chord, a lead handoff is
unclear, or an upper ridge could be a harmonic, open the PNG from
`reconstruct_view {workDir, start, end, output, render: true}`. Start with a
1–3 second window. Time is in source seconds unless you set `time: "project"`.
Warped runs label both clocks. Select a mapped part with `stem`, and use
`parts` or `lanes` to reduce overlay clutter.

- Read the left edges of the bars against the bright audio ridges. Cyan
  outlines are Suno, dotted cream outlines are imported transcriptions, and
  purple dash-dot outlines are edits. Coral dashes flag dropped or merged
  notes. Filled lane colors show the allocated or voiced gates, with patch
  changes in the rows below.
- Use a full ID from the JSON legend as `harmonics` to mark the expected
  partials of a suspected fundamental. Check whether the upper ridge
  shares its attack, movement and release. A separate onset can support an
  independent note. `harmonicSum: true` can help locate a fundamental.
- Compare the source and warmed chip render on the same pitch and time axes.
  Each uses its own top-30 dB display scale. Follow the full ID in
  `reconstruct_trace` to find the stage responsible for a changed gate or
  missing note, then listen to the passage before accepting a repair.

CLI equivalent:

```sh
java -jar "$jar" reconstruct view "$work" --start 45.3 --end 48.8 \
  --stem 0 --parts 0 --harmonics "$note_id" --render "$work/evidence.png"
```

Use `--notes FILE` (`notes` in MCP) to overlay aligned MIDI or JSON note
triples without rebuilding. Repeat `--harmonics-at PITCH[@TIME]`
(`harmonicsAt` array) to probe pitches without trace IDs. `--ridges`
(`ridges`) exports the top three interpolated peaks per column in both
clocks, including the chip panel when rendered. Use `--ridge-count N`
(`ridgeCount`, 1–32) to change the count. A selected note or pitch adds
fundamental, second and third partial trajectories. Use `--db` (`db`)
for per-panel spectral peaks and a selected fundamental's absolute dBFS
curve. Use `--shared-db` (`sharedDb`) for a common color scale and
`--pitch-preset drums` (`pitchPreset: "drums"`) for MIDI 35–81. The view
draws authored bends and assembled FM pitch curves.

Use `trace --lane PSG3` for automatically routed cymbals and `trace --lane DAC`
or the view's drum rows for remaining DAC losses. The
[PSG3 hat recipe](../../docs/architecture/reconstruction-stages.md#tune-or-override-psg3-hats)
shows how to tune fitted gains or override routing with pins. Check
`lanes.PSG3.psgPercussion` for the noise mode and automatic closed-hat
`hatAttackPeriod`, `hatDecayPeriod` and `hatSwitchTick` (defaults 8, 16 and 1).
On DAC, use
`drumGainOverrides` with `hitIds` or a half-open source-time `span` to replace
the gain for selected DAC hits.

When comparing a stem with the render, pass the lanes that carry that part
(for example `--lanes FM1,FM3`). The chip panel then solos them instead of
playing the full mix, where bass and drums blur the comparison.

## Compare an optional stem transcriber

Keep Suno MIDI as the default. If `transcribers_list` reports a configured
tool, make a second reconstruction in a fresh work directory using the same
inputs and the aligned melodic stems. For example, pass
`options: {transcriber: "muscriptor", transcribeParts: {"0": "lead"}}` and
include part 0 in `inputs.stems`. The CLI equivalent is
`--transcriber muscriptor --transcribe-part 0=lead --stem "0=$lead"`.
For an aligned tonal stem listed as `stem_without_part`, opt in at import
with `--transcribe-unmapped muscriptor` (MCP `transcribeUnmapped: "muscriptor"`).
On a fresh run use `--transcriber muscriptor --transcribe-unmapped keyboard=keyboard.wav`
(MCP `options.transcribeUnmapped` maps absolute WAV paths to roles). The added
part and its audio and note provenance are saved for replay. Vocals and FX
stay out of automatic transcription. The importer joins same-pitch holds
when the stem sustains through the boundary and removes unisons mostly
covered by MIDI. Automatic PSG fitting prefers envelopes with an audible
attack and no silent pulses. The added stem defaults to backing when the
MIDI already has tonal notes. Use a role edit to assign it the lead or bass.

Use `chordAudit` in the JSON description (CLI `reconstruct describe WORK_DIR --json`,
MCP `reconstruct_describe` with `json: true`) or the assembly report to locate
dropped upper voices and guide a targeted repair. The audit is song-wide
and uses the effective musical section boundaries even when the note view is filtered.

Use `reconstruct_describe` for the same part and bars in both runs, then
`render_clip` with `solo: ["FM1"]` and `render_compare` to audition each lead
against the aligned stem. Use each run's source-to-project timing if it was
warped. Compare pitch, attack, release and dropped phrases before choosing.
The score and report identify the external note input and its provenance.

## Plan lead and backing

Write `bars` in `roleplan.json` as a string such as `"6-7"`.

The default role pass decides note by note. On merged Suno parts it can
hand the lead between instruments mid-phrase, or promote chord tops and
fills to melody. Make the decision the way an arranger would, per section
and part, in `roleplan.json`. The code still traces individual notes.
For a known song-wide lead, use `--lead-part PART` with staged
`reconstruct run` or `reconstruct transcribe`. A scoped roleplan melody
rule takes precedence within its section, bar and time bounds. Backing
parts stay available for allocation. A `--stem PART=WAV` mapping supplies
audio evidence without declaring the lead.

Check `fast-run` and `fast-retrigger` entries in `reconstruct describe`.
Listen to the mapped stem and the same span in the FM1 solo. A scale run
has short, separate pitch centers and a retrigger burst has repeated
attacks at one pitch. A bend has a moving harmonic ridge within one
attack. Check upper partials together when a pad dominates the stem,
and listen for genuine gaps between same-pitch attacks. Review
`figureVoices` when separate notes share the figure's motion. An
octave partial alone cannot tell you whether an independent voice
doubled the note. Check source
timing in `score.json` and project ticks, retained step indexes and
lane in `report.json`. Use `--no-fast-runs` on a fresh staged run for
a comparison. Use nearby occurrences as extra evidence when the local
line is masked, and preserve variations supported by the local audio.

1. Read the score: `reconstruct_describe {workDir}` (CLI
   `reconstruct describe "$work" --out "$work/describe.txt"`). A full song
   is about 30 KB. Start with the header and the per-part summary lines,
   then drill into specific sections with `section`, `parts` and `bars`.
2. For each section, decide:
   - **Lead.** Look for a singable line: mostly single notes, stepwise
     motion, longer notes on phrase ends, and usually the highest sustained
     line. Repeated chord stabs (`[A#3+D4+F4]` on every other step) are
     backing even when they sit on top. A part that goes quiet or turns
     into pads hands the lead to another part. Some sections have no lead.
   - **Bass.** Usually the lowest monophonic part. Assign it with
     `voice: "all"` when the part is truly monophonic.
   - **Backing.** Everything else is harmony.
3. Create a starting plan with `reconstruct_plan_template` (CLI
   `reconstruct plan-template "$work"`). The CLI prints the default choices
   to stdout. Add `--out "$work/roleplan.json"` to save them as a new plan.
   Change only the sections where the score view shows the default is
   wrong, and keep each rule as narrow as the evidence allows. On the
   benchmark, blanket per-section rules helped merged Suno-like parts but
   lowered melody accuracy where the default was already right. The last
   matching rule wins. Typical rules:
   - Lead among chord stabs in the same register: `{"sections":
     ["section-b14"], "part": 0, "role": "melody", "voice": "top",
     "texture": "single"}`. A note is `chord` when another note of the
     same part attacks on the same 16th step (a bracketed group in the
     grid), otherwise `single`. The summary line counts both per part.
   - Lead above the chord register: `{"sections": ["section-b22"], "part": 0,
     "role": "melody", "voice": "top", "pitchMin": 70}`. Pick `pitchMin`
     from the grid, just under the lowest lead note and above the chord tops.
   - A clean monophonic lead section: `{"sections": ["section-b57"], "part": 0,
     "role": "melody", "voice": "all"}`.
   - Demote a part while another carries the tune: `{"sections": ["section-b90"],
     "part": 3, "role": "harmony", "voice": "all"}`.
   - For a time-only rule, use `startSeconds` or `endSeconds` in source
     seconds with no section or bar selector. Choose `timeBase: "project"`
     for project-clock bounds. For a section or bar rule with time bounds,
     specify the plan's time base explicitly. Check `rolePlanTimes` for the
     resolved project ticks.
4. Validate with `artifact_write` (artifact `roleplan`), then run from
   `roles`. Check `roles.json` reasons (`roleplan#N`) and the FM1 notes in
   `allocation.json` for a sample window. The lead lane should hold one
   part's line, with no octave jumps into chord tones.
5. Solo the lead (`render_clip` with `solo: ["FM1"]`) and compare the same
   window with the reference.

Limits:
- Leave a section with no rule when the lead is not clearly separable.
  The default pass still applies there.
- Suno MIDI often starts a held lead note together with a chord, or
  merges it with a chord tone of the same pitch. Such notes count as
  `chord`, and a `pitchMin` above them drops them from the lead, so the
  melody seems to vanish into the backing. Check a lead solo against the
  stem before raising `pitchMin` past a held note. In the stem, the lead
  often has vibrato and the chords do not. For a mapped stem, the roles
  pass also looks for clearly accented alternatives with independent pitch
  energy. Check the stem and FM1 together when two notes have similar
  accents or share overtones.
- Suno MIDI may have no vocal part. When a vocal carries the tune, either
  write no lead for that section or ask the user which instrument should
  take the vocal line.
- Re-planning reruns allocation and voicing. Use `--keep-voicing` to carry
  compatible settings forward while checking the new allocation.

## Problem → artifact

| Problem | Edit | Resume |
| --- | --- | --- |
| Wrong, missing or mistimed source notes | `edits.json` via `insertNote`, `deleteNote` or `setNote` | `transcribe` |
| Absorbed chord attacks or extended merge anchors | `edits.json` via `unmerge` | `roles` |
| A note needs an explicit pitch curve | `edits.json` via `bend` | `transcribe` |
| Lead swaps parts, chord tones in the lead, or backing taken as melody | `roleplan.json` section and part rules. See **Plan lead and backing**. | `roles` |
| A phrase's notes have the wrong role or lane | `edits.json` via `setRole` with `fromRole`, `swapRoles` (destination notes go to harmony), or `pinLane`. | `roles` |
| Attacks need a velocity change or a fixed release | `setNote` with `velocity` or `noExtension: true`. Use `setVelocity` with `noteIds` or a source span and `velocity` or `scale` to change a group. | `transcribe` |
| Kick, snare or hat level | `voicing.json` with per-pitch `drumGains` on DAC or PSG3. For example, `{"42":0.1}` attenuates the closed hi-hat. | `assemble` |
| Channel overflow, audible drops or clipped note lengths | `allocation.json` lane, timing and drop decisions | Run standalone `voice` to refit, then resume at `assemble` |
| Wrong instrument, plain lead, missing vibrato, excessive ring | `voicing.json` lane variant ID, gain, gate, release rate, vibrato or copy effects. Use catalogue search and audition to check candidate variants. | `assemble`. `run --from voice` also reuses the edit. |
| A patch fades during a held phrase, or a few notes need different gain, gate, release or vibrato | Add `overrides` to the affected lane in `voicing.json`. Use **Audition a phrase** below. | `assemble` |
| Move, repeat or regroup sections | Project JSON, Studio, or the CLI `group` commands | Validate and render the edited project directly |

For example, call `reconstruct_edit` with
`{workDir, propose: true, edit: {id: "answer", reason: "separate attack in the stem", op: "insertNote", part: 0, start: 12.5, end: 12.8, pitch: 68, role: "melody", lane: "FM1"}}`.
The CLI equivalent is `reconstruct edit propose WORK_DIR JSON`; use
`--file batch.json` for a batch. Preview with
`reconstruct run WORK_DIR --with-proposals --id answer` or `--group GROUP`.
Use `reconstruct trace WORK_DIR --by-onset --start S --end S --part N`
(MCP `reconstruct_trace` with `byOnset: true`) to see pitch, assigned role,
lane, fate and stable ID for each attack cluster. Use a trace note ID or
`selector: {at: 43.09, pitch: 80, part: 0}`
for a unique attack within 20 ms. `deleteNote` also accepts generated
roles-stage IDs. Inline CLI `edit add JSON` takes one intent object. Use
`edits: [...]` in MCP or
`reconstruct edit "$work" add --file batch.json` to append an array in one
call. For `setRole`, use a `selectors` array or apply `voice: "top"`,
`"bottom"`, or a zero-based register index to every attack cluster in a span.
Use `others: "harmony"` to reassign the remaining notes in each cluster.
Preview resolved IDs and role changes with `add --dry-run` or MCP `dryRun: true`.
Times default to source seconds. Set `timeBase: "project"` to use the project clock.
Use `splitNote` with `noteId` or a point selector and `at`. The two
attacks have IDs `edit-<id>-left` and `edit-<id>-right`. Optional `pitch`
retunes the right half. Use `mergeNotes` with `noteIds`, point `selectors`,
or `span`, `part` and `pitch` to sustain repeated attacks. The first attack
keeps its ID. For different pitches, use `allowDifferentPitches: true` for a
fixed pitch or `glide: true` for an FM slide. Preview `resultNotes`,
`removedNotes` and conflicts before writing.
Use `pinLane` with `laneType: "FM" | "PSG" | "NOISE" | "DAC"` to route
IDs, point selectors or a filtered span. `PSG` is tonal. `NOISE` offers
white noise for drums and tunable periodic noise for eligible tonal pitches
on PSG3. Drums can also use DAC. The allocator retains an available
previous lane of the requested type and reports any dropped notes.
For a pitch slide to an existing attack, use `glide` with `fromNoteId`,
`toNoteId`, and optional `shape` and `seconds`. Check `lastRun` for orphaned
or invalid targets and inspect the edited passage and any `outsideSpan` changes.
The [reconstruction stages guide](../../docs/architecture/reconstruction-stages.md#persistent-musical-edits)
lists the fields for every operation.

Standalone `voice` refits lane-wide choices and preserves scoped overrides
and compatible gains. A rebuild from an upstream stage can relevel lanes
whose allocated material changed. Pass `--lane FM1` to limit a refit and
`--refit-levels` to recalculate levels. Use `--keep-voicing` with `run --from roles`
or `rerun` to reuse accepted settings and see which stale part entries were
dropped. MCP resumed runs expose `options.refitLanes`, `options.refitLevels`
and `options.keepVoicing`. Read the variant and level changes in the result. For a fresh fit, back up and move
`voicing.json` before running `voice`. A chained run starting at `voice` reuses
existing voicing unless refit options are supplied. Assembly regenerates the
arrangement with saved clip anchors. Check `sustainProbe` and
`voicingWarnings` in the assembly report for FM1 patch dips. Apply direct project and group edits after the final assembly,
or work on a separate copy.

## Review source voices before assigning chip instruments

Use `reconstruct voices WORK_DIR` or MCP `reconstruct_voices {workDir}` to
review names, note IDs, part, stem, role, section and attack evidence, and
confidence. A mixed MIDI part can propose a lead line and separate chords,
register bands, or voices supported by distinct stem evidence. Check the
line's membership before assigning a fixed instrument. The proposal alone
preserves the existing chip allocation and voicing.

Use `voices WORK_DIR create|rename|merge|split|move|assign JSON` or MCP
`reconstruct_voices {workDir, action, entries}`. Give each entry a unique
`id`, `voiceId`, and `author: "human"` or `"agent"`. Move or split notes by
`noteIds`, point `selectors`, or a half-open source-time `span` with filters.
Assign with `assignment: {laneType: "FM", instrument: "VARIANT_ID"}` or
`{laneType: "NOISE", instrument: "closed-hat"}`. Use `clear: true` to remove
an assignment. See the [voices stage](../../docs/architecture/reconstruction-stages.md#identify-and-assign-voices)
for all fields and instrument types.

Resume from `voices` to apply the journal or to pick up edited section
boundaries. Review `issues` for orphaned edits if a split changes a voice ID.
Use trace to check for notes that could not be placed on the requested hardware.
Use `view --color-by voice` or MCP `colorBy: "voice"` to follow a source
instrument across lanes. Inspect `voiceAssignmentExtraClips` in the report
when voices with different instruments share a lane.

## Fit a chord on one channel

Use `reconstruct_edit` with `op: "arpeggio"` or `op: "chordInstrument"` and a
stable edit ID and reason. Select the co-attacking tones with `noteIds`, or
use `part` with a source-time `span: [start, end]` or
`span: {"section":"chorus-1"}`. Use exact IDs to keep an independent lead out
of the group. An arpeggio takes `rate` in driver ticks (try 2) and `order`
(`up`, `down`, `up-down`). A chord instrument needs an FM lane and supports
major, minor, sus, power and octave voicings within YM2612 pitch limits.
Use a compatible `pinLane` with `laneType` on any member to constrain the
whole chord. A conflicting pin leaves the individual notes in allocation.

Preview with `dryRun: true` or CLI `reconstruct edit WORK_DIR add --dry-run JSON`.
For shared work, save a proposal and use `withProposals: true` to audition it.
In your own copy, use `rerun: true` or
`reconstruct run --from allocate WORK_DIR --keep-voicing`. Check refusals in
`edit-results.json` and the merged tone fates and gates in `trace` or `view`.
Compare `arpeggioExtraNoteBytes` and `bankBytes` in the report. Audition a chip
clip and a level-matched source comparison before choosing a voicing.
See [one-channel chord voicing](../../docs/architecture/reconstruction-stages.md#one-channel-chord-voicing).

## Audition a phrase

1. Use `catalog_search` to find FM variant IDs. Compare the current variant
   and challengers with `reconstruct_audition {workDir, lane, start, end,
   variants, solo, reference, output, rmsJson}`. Use source seconds by default
   or set `time: "project"` (CLI `--time project`). Include the attack of a
   long hold, or replace the bounds with `note: TRACE_ID` (CLI `--note`) to
   audition its assembled voiced gate and infer the FM lane.
   Omit `solo` to retain the frozen arrangement in the mix. An optional aligned
   reference is appended after the chip phrase at a matched RMS level.

   ```sh
   java -jar "$jar" reconstruct audition "$work" --lane FM1 --start 45.3 --end 48.8 \
     --variant "$current" --variant "$candidate" --solo --reference "$mix" \
     --rms-json "$work/lead.rms.json" "$work/lead.wav"
   ```

2. Match each numbered WAV to its variant ID and check the successive
   pre-match RMS bins for a mid-hold dip. A `voice` warning identifies an
   FM1 dip above 6 dB and the flattest audible shortlist alternative. Compare
   candidates in the mix before editing the artifact.
3. Add an entry such as `{"startSeconds":45.3,"endSeconds":48.8,"variantId":"…"}`
   to `lanes.FM1.overrides`. For an existing lane without an `overrides` member,
   use `artifact_write` to replace the lane object with the updated one.
   To target a specific attack, take its ID from `allocation.json` and use
   `noteIds`. Entries can overlap if they set different fields. Assemble
   and check the passage again.

Audition up to 32 variants or pass `--shortlist` to use the saved candidates
on the explicit or inferred lane. Run `reconstruct voice WORK_DIR --lane FM1 --variant ID` to
set a lane-wide candidate and fit its level, then assemble. Use
`catalog show INDEX.json ID --brief` for occurrence counts.

For native F0 vibrato, `delay`, `rate` and `steps` count sequencer ticks
(1/60 s at reconstruction tempo zero). Signed `delta` is a packed-frequency
increment. When `rate` divides `steps`, the period is `2 × steps` ticks,
with peak-to-peak excursion `abs(delta) × steps / rate` in native units.
For a held FM lead, use a `noteIds` scope such as
`{"noteIds":["LEAD_ID"],"vibrato":{"delay":1,"rate":1,"delta":8,"steps":6}}`
for about 48 cents at 5 Hz on MIDI 65. Use the
[Studio vibrato conversion](../../docs/architecture/reconstruction-stages.md#studio-vibrato-gesture)
to translate other cents and Hz values to F0.

## Name and reuse musical passages

Use `reconstruct sections WORK_DIR` or MCP `reconstruct_sections` to obtain
source/project times, bar anchors, boundary confidence, and stable IDs for
proposed sections, periods and phrases. Inspect the bands with
`view --sections` or select a span via `view --section ID` and
`render-compare --section ID` (MCP `section`). Use IDs when letter labels
repeat. To save a designation, use `sections add|set|remove|rename WORK_DIR
JSON` or MCP `reconstruct_sections` with `action` and an `entries` array.
Include an `author` (`human` or `agent`) and `reason`. Add/set also take
`id`, `level`, `name`, `sourceStart` and `sourceEnd`. A set batch can
replace a span with two spans or extend one and mark the other `removed:true`.
The journal survives reruns. Review anchor issues if the source grid changes.
Use `"section":"chorus-1"` in a roleplan rule or
`"span":{"section":"chorus-1"}` for an edit, then resume the consuming stage.
See the [musical sections guide](../../docs/architecture/reconstruction-stages.md#musical-sections).

## Studio review

Studio writes note, role, lane, voice and note-voicing intents to the shared
`edits.json` on **Apply**. Section mutations go directly to `sections.json`.
A voice operation is wrapped in `{"op":"voiceEdit","voiceEdit":{...}}`
with the outer intent supplying ID, reason and author. Voice-scoped instruments
replay at `voices`. `noteVoicing` takes `noteIds` and an `instrument` plus
`laneType`, a native `vibrato` object, or `modulationOff: true`. It resolves
against the current allocation at `assemble`. New
Studio intents include `"author":"human"`. An open window shows agent
proposals as magenta dashed outlines and lists them by group in **Proposals**.
**Preview A/B** auditions a proposal against the built span. The reviewer can
accept into pending edits, then **Apply**, or reject from the shared journal.
If the reviewer delegates the decision, use the MCP actions `accept` or
`reject` or `reconstruct proposals accept|reject WORK_DIR ID`
(with `--group GROUP` to select a batch).

1. Open the same `arrangement.smps.json` in Studio. Attach the mix with
   `reference_add` or CLI `reference add PROJECT WAV` if needed.
2. Fetch open items with `notes_list {project, openOnly: true}` or CLI
   `notes list PROJECT --open`. Read the note ID, text, start/end seconds,
   and any lane or clip ID. Use `notes_add` to flag another passage.
3. MCP reassembly retains references and review notes and checks the project
   revision at publication. For CLI-only iteration, keep the shared review
   project separate from the generated work directory and preserve its review
   metadata when transferring arrangement changes. If Studio has unsaved
   edits, coordinate the reload and reconcile them before saving.
4. Resolve with `notes_resolve {project, id, reply, author}` or CLI
   `notes resolve PROJECT ID --reply "Shortened FM1's gate at 0:08–0:20" --author agent`.

For a soloed excerpt in project seconds or an A/B pair in source seconds:

```sh
java -jar "$jar" render-project "$project" "$work/review.wav" --start 8 --end 20 --solo FM1
java -jar "$jar" render-compare "$work" --start 8 --end 20 "$work/comparison.mp3"
```

`render-project` also accepts `--mute LANES`. Pass `--force` to CLI
`view`, `render-project` or `render-compare` to replace completed exports.
`render-compare` uses the saved steady reference for warped runs. Use
`--time project` (MCP `time: "project"`) to choose project seconds for
comparisons or evidence views.
Their results include both clocks. Select another aligned recording with
`--reference WAV` or a replacement project-time render with `--render WAV`.
Choose `.wav` when ffmpeg is unavailable.

## Export the finished arrangement

Use `export WORK_DIR OUTPUT --format wav|mp3|vgm|smps|package` or MCP
`project_export` with `workDir` (or `project`). WAV/MP3 use ffmpeg and accept
project seconds via `--start`, `--end` and `--fade`, native `--loops`, and
`--stems [--zip]`. Use the package for projects with a native bank or declared
chip assets. See the [format notes](../../headless/README.md#export-project-output).

A Studio hand edit can detach the final arrangement from Analysis. Preserve a
copy before rebuilding over it. Use `--overwrite-detached` with a resumed run,
assemble, or an in-place rerun only when authorized. MCP uses
`options.overwriteDetached` for `reconstruct_run` and `reconstruct_stage`.
`reconstruct_rerun` takes `workDir`, optional new `output`, optional
`search` directories and `keepVoicing`. Omit `output` and set
`overwriteDetached` to rebuild in place using the saved score inputs.

## Gate pipeline code changes

Run `bench run MANIFEST CATALOGUE OUTPUT --quick` before and after with the
same corpus, catalogue, case selection and options. Compare scorecards with
`bench_compare {old, new, tolerance}` or
`bench compare BASELINE/scorecard.json CANDIDATE/scorecard.json --tolerance 0`
when output should be equivalent. Inspect per-case and slice changes as well
as the headline gate. A failing gate needs investigation before delivery.

## Hard rules

- Edit source or validated artifacts. Never hand-edit compiled SMPS bytes.
- Validate after every edit. Use `project_validate` or `validate PROJECT` for
  source edits, and validate the regenerated project after artifact edits.
- Keep private audio, stems, path-bearing artifacts, and benchmark data out of
  public repositories. Share project and playback files only as authorized.

In the OpenSMPSDeck checkout, read `docs/user-guide/agents.md` for setup,
`headless/README.md` for commands, and the stage, review and benchmark
guides under `docs/architecture/` for field and measurement details.
