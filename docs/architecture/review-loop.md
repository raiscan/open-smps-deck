# Studio review loop

Studio and external agents share a reconstruction work directory and its
`arrangement.smps.json`. The human can save time-coded feedback in the
project. Musical proposals live in the work directory's `edits.json`.
See the [project format](../../project-core/README.md#review-metadata-in-versions-16)
and [Studio guide](../user-guide/studio.md#inspect-and-edit-a-reconstruction).

## Agent workflow

Run these subcommands with the packaged headless JAR. Use the
[external-agent guide](../user-guide/agents.md#drive-the-product-loop) for MCP.

1. Read `notes list WORK_DIR/arrangement.smps.json --open`. Use `startSeconds` and optional
   `endSeconds` to target the passage. A `laneId` or `clipId` can identify
   the part. Keep the note ID for the response.
2. Run `reconstruct view WORK_DIR --time project --start S --end E --render OUT.png`
   at the review note's project times. The spectrum, note outlines and lane
   gates share the same time axis. Add `--harmonics NOTE_ID` to compare a
   suspected partial with its fundamental. Use legend IDs with
   `reconstruct trace` to find the affected stage.
3. Write a scoped proposal with
   `reconstruct edit propose WORK_DIR --file batch.json`. Preview it with
   `reconstruct run WORK_DIR --with-proposals`. Compare the sibling preview
   to the built baseline with `reconstruct trace-diff`, using a source-time
   `--span` to check out-of-span notes, lanes, voicing and compiled events.
   The human reviews the A/B clips or opens **Proposals** in Studio.
4. After approval, accept the selected proposal and rebuild from its
   reported stage, or use **Accept** then **Apply** in Studio. Validate the
   rebuilt project with `validate-project`. Use `render-compare` for an
   aligned clip and reference. In Studio use a loop and **R** to switch
   between the reference and chip at the same playhead position.
5. Once the reviewer confirms the change, run
   `notes resolve WORK_DIR/arrangement.smps.json NOTE-ID --reply "Shortened the FM1 attack" --author agent`.
   The original request and its timestamp stay with the reply. Use
   `notes add` with `--author agent` to flag a new passage.

Assembly carries forward saved references and notes. Studio watches atomic
replacements. A clean session adopts a new revision and updates the markers
and open list. Unsaved edits remain in memory if disk changes. **Reload file
+ keep local recovery** writes a sibling copy and then adopts disk. For an
Analysis draft conflict, use **Resolve conflict…** to reconcile with the
latest journal. Revision checks protect both files from stale writes.

References are local WAV paths. Share the audio separately and use relative
paths when sharing a project directory. Keep all audio, evidence PNGs and
private source paths out of the public checkout. Studio resamples the
selected reference to its 48 kHz stereo audition clock. Rendered and
reference audio share project time through source switches and loop wraps.
Render lane masks mute hardware output while the sequencer advances.
