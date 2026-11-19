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
- [ ] Listen to the same time range in the S3K render and source. Use
  `render_clip` on `arrangement.smps.json` with `start`, `end` in project
  seconds and a new relative WAV filename in `output`. Use `solo: ["FM1"]`
  to isolate the lead or `mute: ["FM1"]` to hear the backing. Use
  `render_compare` with the full `render.wav`, reference WAV, `workDir`,
  and the same range to write a level-matched render-then-reference WAV.
  Apply the reference's offset if its time zero is shifted.
- [ ] Edit the owning artifact using `artifact_write`. Send a complete
  versioned object or a replacement at an existing `selection`. Fix any
  reported validation errors before rerunning.
- [ ] Run from the first consumer shown below using `reconstruct_run` with
  `from` and `workDir` (CLI `reconstruct run --from STAGE "$work"`).
  Validate the rebuilt project, render the review range again, and compare
  it with the previous clip and the source.
- [ ] Reply after verifying the change. Mention the specific change, lane
  and time range, and the clip filename. Keep unresolved issues open.

## Problem → artifact

| Problem | Edit | Resume |
| --- | --- | --- |
| Wrong, missing or mistimed notes | `score.json` note events, or `structure.json` effective notes and their associated repeat corrections | `structure` for score, `roles` for structure |
| Melody assigned to the wrong lane or buried by role priority | `roles.json` note roles and priorities. Check the resulting allocation. Use voicing gain for a correctly assigned lead that is simply too quiet. | `allocate` |
| Channel overflow, audible drops or clipped note lengths | `allocation.json` lane, timing and drop decisions | Run standalone `voice` to refit, then resume at `assemble` |
| Wrong instrument, plain lead, missing vibrato, excessive ring | `voicing.json` lane variant ID, gain, gate, release rate, vibrato or copy effects. Use catalogue search and audition to check candidate variants. | `assemble`. `run --from voice` also reuses the edit. |
| Move, repeat or regroup sections | Project JSON, Studio, or the CLI `group` commands | Validate and render the edited project directly |

Standalone `voice` refits and replaces existing voicing. A chained run
starting at `voice` reuses an existing hand-edited voicing. Starting at an
earlier stage refits later artifacts. Save any downstream edits you intend
to carry forward. Assembly regenerates the arrangement. Apply direct
project and group edits after the final assembly, or work on a separate copy.

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
