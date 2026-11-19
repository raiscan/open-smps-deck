# Studio review loop

Studio and external agents share one `.smps.json` file. The human saves
time-coded feedback in the project, and the agent records a resolution there.
The [project format](../../project-core/README.md#review-metadata-in-versions-15)
defines the fields. The [Studio guide](../user-guide/studio.md#review-a-reconstruction)
covers the audition and editing controls.

## Agent workflow

Run the following subcommands with the packaged headless JAR.

1. Read `notes list song.smps.json --open`. Use `startSeconds` and optional
   `endSeconds` to target the passage. A `laneId` or `clipId` can identify the
   part. Keep the note ID for the response.
2. Read the current project and update the arrangement while preserving the
   `notes` and `references` arrays and existing IDs. Use a revision-checked
   write for changes to the shared file. Reread and reconcile if another writer
   changed the project before the write.
3. Validate with `validate-project song.smps.json` and listen to the result of
   `render-project song.smps.json revision.wav`. Compare the passage against
   its reference using the declared offset. The renderer exports the SMPS mix.
4. Once the change is verified, run
   `notes resolve song.smps.json NOTE-ID --reply "Shortened the FM1 attack" --author agent`.
   The original request and its timestamp are retained alongside the reply.
   Leave the note open if it still needs investigation. To flag a new passage,
   use `notes add` with `--author agent`.

Studio watches atomic replacements. A clean session adopts the new revision
and updates the markers and open list. An unsaved edit remains in memory if a
new disk revision arrives. **Reload file + keep local recovery** writes a sibling
copy of the local edits and then adopts disk. CLI writes also require a matching
revision. This keeps stale saves from silently overwriting feedback.

References are local WAV paths. Share the audio separately and use relative
paths when sharing a project directory. Studio decodes the selected WAV on a
worker, draws its cached per-column peaks, and resamples it to the 48 kHz
stereo audition clock. Render and reference share the same project-time
cursor through source switches and loop wraps. Render lane masks mute
hardware output during audition while the sequencer continues to advance.
