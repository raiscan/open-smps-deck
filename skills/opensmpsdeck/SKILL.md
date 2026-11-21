---
name: opensmpsdeck
description: Reconstruct aligned Suno MIDI, mix WAV, and optional stems into an S3K SMPS arrangement with OpenSMPSDeck. Use to diagnose or edit stage artifacts, audition chip renders, address Studio review notes, or benchmark reconstruction changes.
---

# Reconstruct and review an S3K arrangement

Use the local OpenSMPSDeck checkout and its packaged headless JAR. Prefer the
`opensmpsdeck` MCP tools when configured. Otherwise run commands with
`java -jar "$jar"`, where `jar` is the absolute path to the headless JAR.

## Checklist

- [ ] Identify the MIDI, mix WAV, optional stems and local instrument catalogue.
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
- [ ] Save note repairs with `reconstruct_edit`. Give each intent a stable
  `id` and a `reason` describing the evidence. For plans and sound choices,
  use `artifact_write` on the owning artifact. Fix validation errors before
  listening to the revision.
- [ ] Pass `rerun: true` to `reconstruct_edit` to rebuild and inspect its
  `traceDiff`. For other artifact changes, resume with `reconstruct_run`
  using `from` and `workDir` (CLI `reconstruct run --from STAGE "$work"`).
  Validate the rebuilt project, then compare the revised render with the
  previous clip and the source.
- [ ] Reply after verifying the change. Mention the specific change, lane
  and time range, and the clip filename. Keep unresolved issues open.

## Trace a missing attack or changed gate

- Use `reconstruct_trace {workDir, start, end, part, pitch}` before editing a
  missing, misplaced, or clipped note. Times filter in source seconds.
  Add `lane` or `status` to narrow the result. The trace carries source and
  project seconds, placement ticks, role decisions, merge partners,
  allocation competitors, and the final voiced gate and bend.
- After a focused repair, compare separate baseline and candidate work
  directories with `reconstruct_trace_diff {oldWorkDir, newWorkDir, span}`.
  Use `span: "START,END"` in source seconds to flag changes outside the
  edited passage. Check `lanePermutations` for grouped swaps, the first
  affected note IDs, and suggested `pinLane` edits. Check `voicingChanges`
  for variant and level changes.
- Use `reconstruct rerun "$work" "$fresh" --search "$input_dir"` to replay
  the inputs and options of a historical fresh run. The private
  `manifest.json` pins input hashes and retains imported note snapshots.
  Replay carries `edits.json` into the fresh directory. Use `run --from`
  in the current work directory to retain its role plan and voicing.

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
Use `reconstruct_describe` for the same part and bars in both runs, then
`render_clip` with `solo: ["FM1"]` and `render_compare` to audition each lead
against the aligned stem. Use each run's source-to-project timing if it was
warped. Compare pitch, attack, release and dropped phrases before choosing.
The score and report identify the external note input and its provenance.

## Plan lead and backing

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
   - Use the time base from `reconstruct_describe` for plan time bounds
     (project seconds when warped, source seconds otherwise).
   - Use `bars` or `startSeconds`/`endSeconds` only for a mid-section
     handoff.
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
  often has vibrato and the chords do not.
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
| A few individual notes have the wrong role or lane | `edits.json` via `setRole` or `pinLane`. Check the trace for competing notes. | `roles` |
| Channel overflow, audible drops or clipped note lengths | `allocation.json` lane, timing and drop decisions | Run standalone `voice` to refit, then resume at `assemble` |
| Wrong instrument, plain lead, missing vibrato, excessive ring | `voicing.json` lane variant ID, gain, gate, release rate, vibrato or copy effects. Use catalogue search and audition to check candidate variants. | `assemble`. `run --from voice` also reuses the edit. |
| A patch fades during a held phrase, or a few notes need different gain, gate, release or vibrato | Add `overrides` to the affected lane in `voicing.json`. Use **Audition a phrase** below. | `assemble` |
| Move, repeat or regroup sections | Project JSON, Studio, or the CLI `group` commands | Validate and render the edited project directly |

For example, call `reconstruct_edit` with
`{workDir, action: "add", edit: {id: "answer", reason: "separate attack in the stem", op: "insertNote", part: 0, start: 12.5, end: 12.8, pitch: 68, role: "melody", lane: "FM1"}, rerun: true}`.
Use the trace note ID for an existing attack. Check `lastRun` for orphaned
targets and inspect both the edited passage and any `outsideSpan` changes.
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
arrangement. Apply direct project and group edits after the final assembly,
or work on a separate copy.

## Audition a phrase

1. Use `catalog_search` to find FM variant IDs. Compare the current variant
   and challengers with `reconstruct_audition {workDir, lane, start, end,
   variants, solo, reference, output}`. Use the phrase's project seconds and
   include the start of the long hold so each patch gets its own attack.
   Omit `solo` to retain the frozen arrangement in the mix. An optional aligned
   reference is appended after the chip phrase at a matched RMS level.

   ```sh
   java -jar "$jar" reconstruct audition "$work" --lane FM1 --start 45.3 --end 48.8 \
     --variant "$current" --variant "$candidate" --solo --reference "$mix" "$work/lead.wav"
   ```

2. Listen to the numbered WAVs and check the successive RMS bins for a
   mid-hold dip. The bins describe the chip output before reference matching.
   Compare candidates in the mix before editing the artifact.
3. Add an entry such as `{"startSeconds":45.3,"endSeconds":48.8,"variantId":"…"}`
   to `lanes.FM1.overrides`. For an existing lane without an `overrides` member,
   use `artifact_write` to replace the lane object with the updated one.
   To target a specific attack, take its ID from `allocation.json` and use
   `noteIds`. Assemble and check the passage again.

## Studio review

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

For CLI-only clip extraction, render from time zero so the driver reaches the
right state. For example, to listen to 0:08–0:20:

```sh
java -jar "$jar" render-project "$project" "$work/review-full.wav" --frames 1200
ffmpeg -n -i "$work/review-full.wav" -ss 8 -t 12 -c:a pcm_s16le "$work/review-08-20.wav"
```

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
