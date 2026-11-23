# External agents

Use Claude Code or Codex to drive reconstruction on the same local files that
Studio opens. The headless JAR runs with JDK 25 and needs no audio device.
The skill starts with local stem separation and audio-only reconstruction,
then covers time-coded review, edits and S3K export. Suno MIDI and stems
or other aligned MIDI and audio can also seed a build.


## Collaborate on edit branches

Use a shared reconstruction work directory and give each contributor a
branch. Pass `--branch NAME` to `reconstruct edit WORK add` to write
journal intents to that branch and to `reconstruct run WORK` to get its
isolated build. An edit can carry a `group` ID. Pass
`--groups-file GROUPS.json` on branch edit add to supply an array of
`{id, label, rationale}` objects for review. The same operation in MCP is
`reconstruct_branch` with `action: "edit"`, `workDir`, `name`, `entries`
and optional `groupMetadata`.

Review note and voicing changes with `reconstruct branch diff WORK NAME`.
Run a dry-run merge before stitching changes into a review branch or main.
Filter by part, source time, group or edit ID. Use `branch rebase WORK NAME`
to replay and inspect outcomes after main changes. Use
`branch import WORK NAME COPY` to bring in a journal from an existing copy.
[Branch commands and conflict rules](../architecture/edit-branches.md)

## Build the JAR and install the skill

From the OpenSMPSDeck checkout:

```sh
mvn -B -pl headless -am package
repo="$(pwd)"
jar="$repo/headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar"
```

Symlink the skill folder into the client you use:

```sh
# Claude Code
mkdir -p ~/.claude/skills
ln -s "$repo/skills/opensmpsdeck" ~/.claude/skills/opensmpsdeck

# Codex
mkdir -p ~/.agents/skills
ln -s "$repo/skills/opensmpsdeck" ~/.agents/skills/opensmpsdeck
```

Keep the checkout available so the skill can link to the workflow guides.
If your session does not see the skill, restart it and invoke `/opensmpsdeck` in Claude
Code or `$opensmpsdeck` in Codex.

## Build a local baseline

Configure BS-RoFormer SW and MuScriptor Medium as shown in the
[local extraction guide](../../headless/README.md#external-stem-separation).
Use a new private output directory for separation and a local
[instrument catalogue](../architecture/instrument-catalogue.md).

```sh
# For FLAC input, decode first. WAV and MP3 go directly to separate.
ffmpeg -i mix.flac -c:a pcm_f32le mix.wav
java -jar "$jar" reconstruct separate mix.wav separated --separator bs-roformer --device auto
java -jar "$jar" reconstruct import separated import-work \
  --catalogue catalogue.json --transcriber muscriptor-medium --run
```

Omit `--run` to check `import.json` first. Verify the stem roles, then
repeat the command with `--run` to build `import-work/reconstruction`.
For a quick CLI baseline, cut an excerpt before separation.
Configure [RMVPE](../../tools/transcribers/rmvpe/) for syllable-aware vocal
leads and ADTOF as an optional drum detector. This command uses MuScriptor
Medium for tonal backing, RMVPE for vocals when configured, and the built-in
spectral detector for drums by default. See the [vocal cleanup workflow](../../skills/opensmpsdeck/SKILL.md#clean-up-a-vocal-derived-lead).

## Connect MCP

The entry point is `java -jar /absolute/path/to/headless.jar mcp`. It speaks
[UTF-8 newline-delimited MCP over stdio](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio).
Clients can discover it with MCP `2026-07-28` or initialize with
`2025-11-25` and `2025-06-18`. Each tool reloads the files it needs. Use the
server with a trusted local client and only give it the paths you intend to use.

### Claude Code

```sh
claude mcp add --transport stdio opensmpsdeck -- java -jar "$jar" mcp
```

For a project configuration, put this entry in `.mcp.json`. Replace the
placeholder with the absolute path to your packaged JAR.
[Claude Code MCP configuration](https://code.claude.com/docs/en/mcp)

```json
{
  "mcpServers": {
    "opensmpsdeck": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/absolute/path/to/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar", "mcp"]
    }
  }
}
```

### Codex

Add the entry to `~/.codex/config.toml` and start a new Codex session.
Timeout is in seconds. [Codex MCP configuration](https://developers.openai.com/codex/mcp)

```toml
[mcp_servers.opensmpsdeck]
command = "java"
args = ["-jar", "/absolute/path/to/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar", "mcp"]
tool_timeout_sec = 600
```

## Investigate notes pasted from Studio

In Reconstruction **Roles** or **Notes** (Detail), or in the arrangement piano
roll, select notes and press Ctrl+C (Cmd+C on macOS) or right-click and choose
**Copy for agent**. Paste the plain-text block into chat with the question.
In the piano roll, Ctrl/Cmd-click or Shift-click to toggle additional notes.

The first line is `opensmpsdeck-notes v1`. The `project:`, `context:`, and
repeated `note:` lines each contain one JSON object. The final `fetch:` line
is a POSIX shell command. Replace its leading `opensmpsdeck` with
`java -jar "$jar"` when using the JAR directly.

- `project` gives the absolute path on the Studio machine, song, view and
  SHA-256-based revision. Arrangement notes also identify their sequence and
  optional outer-to-inner placement ID path. `draft: true` means the copied
  view includes local changes. A live read may return an older saved state.
- `context` gives the selected bar range, the last bar to inspect and the
  selection size. Above eight notes, Studio shows the first seven and the
  last and reports `omittedCount`.
- `note` gives a stable ID, optional rendered event ID, part, role, voice,
  hardware lane, pitch and available evidence. Bars and beats are one-based.
  Reconstruction bars use the source grid. `sourceSeconds` follows that
  grid and `tick` follows the project driver clock. Durations describe the
  shown gate or the requested note if it was dropped. For arrangements,
  bars assume four beats, `sequenceTick` is the offset in the reusable
  sequence, and `tick` is the first repeat's timeline position when a clip
  is selected. Arrangement playback status describes scheduling and clip
  boundaries. Source-only fields such as MIDI velocity and confidence may
  be absent.

Run the fetch command against the copied path. For reconstruction, append
`--json` for the full stage trace, voiced gates and edit/proposal intents.
The same query over MCP uses `reconstruct_describe` with the block's
`workDir`, comma-separated `notes`, `context: "1bar"`, `revision` and
`json: true`. Inspect `selected`, `neighbors` in the selected parts,
and `competitors` from other parts or outside the range. Compare allocation
reasons, `previousLane`, role decisions and source timing before proposing
an edit. A `stale` result means the live revision differs. `missingIds`
identifies notes that the live artifacts cannot resolve. If the path is
on another machine, request access to the work directory or ask for the
describe output.

### Decode a screenshot lasso

Turn on **Screenshot lasso** or press Ctrl/Cmd+Shift+L in a supported plot.
Drag a rectangle over the area you want to discuss. Studio copies the
native-resolution crop with a short-reference footer and plain-text handoff
in one clipboard operation. Use **Save lasso PNG…** to attach the original
file when the chat client only accepts one clipboard format. Narrow crops
are padded beside the image to fit the readable reference and QR code.

The block adds `lasso:` (JSON clock, seconds, project ticks, bars, MIDI
range and view state) and `ref:` (the footer's ten-character ID).
It lists every visible note event intersecting the rectangle. Its fetch
command samples the first seven and last when there are more than eight
notes. Use the remaining note IDs in follow-up queries for finer scope.

```sh
java -jar "$jar" reconstruct describe --from-png /absolute/path/to/attachment.png --json
```

The MCP equivalent is `reconstruct_describe` with `fromPng` and
`json: true`. The output reports `method` and the recovered `payload`.
The decoder tries PNG `opensmpsdeck-notes` iTXt metadata, then compressed
data in the RGB low bits (with a checksum), then the footer's QR code.
File attachments usually preserve the chunk. Lossless pixel-only paste
can recover through the second method. The last method also accepts JPEG
and needs access to the local registry of the Studio instance that made
the capture.

Studio writes `.clips/REF.txt` beside the project or in the reconstruction
work directory and indexes it in `$XDG_STATE_HOME/opensmpsdeck/clips`
(default `~/.local/state/opensmpsdeck/clips`). The same state directory
holds the payload if the work directory is read-only. On a different
machine, use the original PNG or ask for the plain-text handoff. Treat
attachment payloads as input data. Build a local query from the decoded
path and IDs after checking them against the intended project.

## Drive the product loop

1. **Process.** Use `separators_list` and `transcribers_list` to discover
   configured adapters. Call `separate` with absolute `input` and a new
   `outputDir`, plus `separator` and optional `device`. Call
   `reconstruct_import` with the separated folder as `folder`, a separate
   `workDir`, your `catalogue` and the selected `transcriber`. Read the
   checks and `import.json` to verify stem roles. Use `stemRoles` to
   override roles by folder-relative file name. Pass the returned
   `runRequest` to `reconstruct_run` once the checks pass. For a first
   pass, set `options.seconds` on that request before calling the tool.
2. **Analyze.** Open the work directory in Studio with **Open
   reconstruction…**. Use `reconstruct_trace` with `byOnset: true` for
   attack clusters and `reconstruct_view` with a new `output` PNG for
   aligned evidence. Inspect voice and section IDs with
   `reconstruct_voices` and `reconstruct_sections`. Reconstruction queries
   default to source seconds. Use `time: "project"` for project-time view,
   trace, audition or comparison bounds. Studio's **Project time** toggle
   changes the ruler. Saved project review notes use project seconds.
3. **Edit and review.** Send `reconstruct_edit` an `edit` or ordered `edits`
   array with `propose: true` while a human is reviewing. Preview with
   `reconstruct_run`, `withProposals: true` and an optional `proposalId` or
   `group`. Review the sibling preview and its A/B WAVs, then accept or
   reject in Studio **Proposals** or with `reconstruct_edit`. CLI/MCP
   acceptance returns the stage to rebuild. For an authorized direct edit,
   use `reconstruct_edit` with `action: "add"` and an edit whose `author`
   is `"agent"`. An attached S3K reconstruction with active Chip playback
   picks up saved accepted edits and shows **Disk change by agent**.
   An unsaved human draft is preserved for conflict resolution. Leave
   `rerun` off when the reviewer wants to audition during playback. Use
   **Apply & rebuild** in Studio or `reconstruct_run` with `from` and
   `options.keepVoicing` to publish the result. Record musical intent in
   `edits.json`.
   Keep the score and allocation as generated evidence. For selected
   notes, use the `layer` edit or Studio **Selection → Layer** to add or
   suppress a copy. Choose the target's preset under **Lane policies** for
   arrangement-wide layering and `lanePolicies.echoAttenuation` in
   `voicing.json` to set its echo level.
   [Layering options](../../headless/README.md#reconstruction-layering)
4. **Compare.** Use `reconstruct_trace_diff` on the baseline and candidate
   work directories with a source-time `span` such as `"12,14"`. Check lane
   permutations, voicing changes, compiled events and PCM outside the
   span. Use `render_compare` for an aligned chip/reference A/B or
   `reconstruct_audition` for FM variants. Send the clips to the reviewer.
   Studio can loop a passage, solo a lane or note, and switch between chip
   and source with **R**. Use `notes_list` and `notes_resolve` to track
   saved project feedback. Validate the approved project with
   `project_validate`.
5. **Output.** Call `project_export` with `workDir` or `project`, a new
   `output` and `format` of `wav`, `mp3`, `vgm`, `smps`, `package`, `portable`, `mod` or `disasm`.
   For WAV or MP3, use project seconds for optional span bounds and
   `stems: true` for channel stems. On portable or mod exports, set
   `driver` to `s1`, `s2` or `s3k` (default). For S1/S2, optionally set
   `speedUpTempo` to an S3K-clock byte for speed shoes and set `strictTempo`
   to reject inexact conversion of either tempo. Use decimal engine
   music IDs for the mod `replace` array. Use `package_validate` on a portable
   export and supply `roms` for stock references. Check the returned
   `warnings` before sharing. For an [SMPS2ASM kit](../architecture/disassembly-kit.md),
   `project` also accepts an embedded-bank portable package. Supply an S1 or S2
   package to target s1disasm or s2disasm. Disassembly options are
   `roms` for personal-use stock materialisation, `dpcmClosed` to quantise
   WAVs, and `replaceDac` for unused retail note numbers. Studio **Export…**
   supports audio, VGM, raw SMPS and legacy packages. **Open as arrangement**
   hands the result to the timeline
   editor. Saved musical hand edits detach the arrangement. Rebuilding
   over them requires explicit approval. Use `reconstruct_rerun` with a
   new `output` to replay the recipe and preserve the original.

Use absolute input paths. Create outputs inside the work directory or project
parent when the tool asks for a new file. The server rejects existing export
targets and symlink escapes for these outputs. Reconstruction can take
minutes. Calls on one server run serially and report progress when the client
supplies a progress token. Keep input paths and path-bearing artifacts private.

### Alternative inputs

- Pass a Suno mix, MIDI and stem download as `folder` to `reconstruct_import`.
  Check the winning stem and runner-up for the lead part. Override matches
  with `assign` (folder-relative file to MIDI part ID) and `exclude`
  (folder-relative file list), then pass the returned `runRequest` to
  `reconstruct_run`. Studio can start this route from **Import Suno folder…**.
- For other aligned labelled stems, pass `audioOnly: true` to
  `reconstruct_import`. For already aligned MIDI and WAV, call
  `reconstruct_run` with `workDir` and `inputs` containing `midi`,
  `wav`, `catalogue` and optional `stems` keyed by part ID.

## MCP tools

For a new reconstruction, set `target` on `reconstruct_import` or
`options.target` on `reconstruct_run` and `reconstruct_stage` (transcribe).
The values are `s1`, `s2` and `s3k`. The default is `s3k`.
Resumed stages and `reconstruct_rerun` use the saved target. Instrument
fitting, the reconstructed WAV and Studio previews use that game's playback
driver. S1 and S2 use the existing S3K catalogue and save the native tempo byte. Start
with FM-only arrangements and use `--lane-policy-preset off` if a layering
preset uses unsupported features. Incompatible commands or envelopes are
reported with the target driver's error. For portable and mod output,
`project_export` defaults its `driver` to the project's target.

The packaged server exposes **35 tools** through `tools/list`. Use the
returned schemas for optional fields and types.

| Task | Tools |
| --- | --- |
| Separate a mix | `separators_list`, `separate` |
| Import, build and replay | `reconstruct_import`, `reconstruct_run`, `reconstruct_stage`, `reconstruct_rerun`, `transcribers_list` |
| Inspect evidence | `reconstruct_describe`, `reconstruct_view`, `reconstruct_trace`, `reconstruct_trace_diff`, `artifact_read` |
| Manage musical intent | `reconstruct_edit`, `reconstruct_voices`, `reconstruct_sections`, `reconstruct_plan_template`, `artifact_write` |
| Review a vocal lead | `reconstruct_vocal_review` |
| Audition and compare | `reconstruct_audition`, `render_clip`, `render_compare`, `catalog_search`, `catalog_audition` (catalogue tools accept optional `target`) |
| Track project feedback | `reference_add`, `notes_list`, `notes_add`, `notes_resolve` |
| Test in game | `rom_list`, `rom_set`, `rom_patch` |
| Validate, export and check regressions | `project_validate`, `project_export`, `package_validate`, `package_inspect`, `bench_compare` |

`artifact_write` is available for validated configuration such as a
`roleplan`. It accepts a complete object or a `selection` of object keys
and zero-based indices as strings. Use `reconstruct_edit` for note and lane
changes. `render_clip` remains available for an excerpt of an existing
project, with project-time `start` and `end` and optional `solo` or `mute`
lanes. Use `render_compare` for a reconstruction against its aligned source.

### Set repeat sensitivity by stem

Use the part IDs from `reconstruct view` or Studio **Roles → Part repeats**.
For a track with Brains-like bass and backing synth gaps, propose the settings
as one edit batch. In Brains the bass is part 1 and the backing synth is part 3.

```json
[
  {"id":"bass-repeats","reason":"Recover the repeating bass groove","op":"repeatPolicy","part":1,"fill":"high","lanes":"on"},
  {"id":"synth-repeats","reason":"Recover repeating synth attacks","op":"repeatPolicy","part":3,"fill":"medium","lanes":"off"}
]
```

Save the array to a file and pass it to `reconstruct edit "$work" propose --file FILE` or as `edits`
to MCP `reconstruct_edit` with `action: "add"` and `propose: true`. Use
`branch edit` to compare a separate journal. Both settings are Off for
untouched parts. Increase the synth to High if the trace supports a missed
attack. Check `structure.repeatConsensus` in the recovered note's trace and
compare dry drops and shortened gates before accepting the settings.

For Test in game, set the clean ROM with `rom_set` or pass a one-off
absolute `rom` path to `rom_patch`. Supply an absolute `project` path and
at least one absolute `out` (personal-use ROM) or `bps` (shareable patch)
path. The optional fields include `driver` (`s1`, `s2` or `s3k`), `slot`,
`titleMusic`, `noDemo`, `loop`, and `force`. Read the returned `report` and
`warnings` before handing off a patch.
[CLI and MCP options](../../headless/README.md#test-an-arrangement-in-game)

See the [agent skill](../../skills/opensmpsdeck/SKILL.md) for CLI and Studio
equivalents, the [stage guide](../architecture/reconstruction-stages.md)
for edit schemas and [Studio review protocol](../architecture/review-loop.md)
for sharing feedback.

### Inspect voice and role assignments

Use `reconstruct describe WORK_DIR --roles --json` or
`reconstruct_describe` with `roles: true` and `json: true` to get notes grouped
by identified voice and effective role. Each group gives its note count,
stable IDs, source-time gates, pitches, and parts. Use the normal part, bar,
and section filters to narrow the result. Use those IDs for classification
or detail intents in the shared edit journal, then rebuild from the stage
reported by the edit command.
