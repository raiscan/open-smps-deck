# External agents

Use Claude Code or Codex to drive reconstruction on the same local files that
Studio opens. The headless JAR runs with JDK 25 and needs no audio device.
The skill starts with local stem separation and audio-only reconstruction,
then covers time-coded review, edits and S3K export. Suno MIDI and stems
or other aligned MIDI and audio can also seed a build.

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
The bake-off recommends Basic Pitch for vocals and ADTOF for drums.
This command uses MuScriptor Medium for all tonal stems and the built-in
spectral detector for drums.

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
   acceptance returns the stage to rebuild. Studio rebuilds when the
   reviewer presses **Apply**. For authorized direct edits, use
   `reconstruct_edit` with `action: "add"` and optional `rerun: true`, or
   resume with `reconstruct_run` and `from`. Use `options.keepVoicing` to
   preserve compatible settings. Record musical intent in `edits.json`.
   Keep the score and allocation as generated evidence. For selected
   notes, use the `layer` edit or Studio **Selection → Layer** to add or
   suppress a copy. Use **Lane policies → S3K layering** for an
   arrangement-wide preset and `lanePolicies.echoAttenuation` in
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
   `output` and `format` of `wav`, `mp3`, `vgm`, `smps`, `package`, `portable` or `mod`.
   For WAV or MP3, use project seconds for optional span bounds and
   `stems: true` for channel stems. On portable or mod exports, set
   `driver` to `s1`, `s2` or `s3k` (default). For S1/S2, optionally set
   `speedUpTempo` to an S3K-clock byte for speed shoes and set `strictTempo`
   to reject inexact conversion of either tempo. Use decimal engine
   music IDs for the mod `replace` array. Use `package_validate` on a portable
   export and supply `roms` for stock references. Check the returned
   `warnings` before sharing. Studio **Export…** supports audio, VGM, raw
   SMPS and legacy packages. **Open as arrangement** hands the result to the timeline
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

The packaged server exposes **34 tools** through `tools/list`. Use the
returned schemas for optional fields and types.

| Task | Tools |
| --- | --- |
| Separate a mix | `separators_list`, `separate` |
| Import, build and replay | `reconstruct_import`, `reconstruct_run`, `reconstruct_stage`, `reconstruct_rerun`, `transcribers_list` |
| Inspect evidence | `reconstruct_describe`, `reconstruct_view`, `reconstruct_trace`, `reconstruct_trace_diff`, `artifact_read` |
| Manage musical intent | `reconstruct_edit`, `reconstruct_voices`, `reconstruct_sections`, `reconstruct_plan_template`, `artifact_write` |
| Audition and compare | `reconstruct_audition`, `render_clip`, `render_compare`, `catalog_search`, `catalog_audition` |
| Track project feedback | `reference_add`, `notes_list`, `notes_add`, `notes_resolve` |
| Test in game | `rom_list`, `rom_set`, `rom_patch` |
| Validate, export and check regressions | `project_validate`, `project_export`, `package_validate`, `package_inspect`, `bench_compare` |

`artifact_write` is available for validated configuration such as a
`roleplan`. It accepts a complete object or a `selection` of object keys
and zero-based indices as strings. Use `reconstruct_edit` for note and lane
changes. `render_clip` remains available for an excerpt of an existing
project, with project-time `start` and `end` and optional `solo` or `mute`
lanes. Use `render_compare` for a reconstruction against its aligned source.

For S3K Test in game, set the clean ROM with `rom_set` or pass a one-off
absolute `rom` path to `rom_patch`. Supply an absolute `project` path and
at least one absolute `out` (personal-use ROM) or `bps` (shareable patch)
path. The optional fields are `slot`, `titleMusic`, `noDemo`, `loop`, and
`force`. Read the returned `report` and `warnings` before handing off a patch.
[CLI and MCP options](../../headless/README.md#test-an-arrangement-in-sonic-3--knuckles)

See the [agent skill](../../skills/opensmpsdeck/SKILL.md) for CLI and Studio
equivalents, the [stage guide](../architecture/reconstruction-stages.md)
for edit schemas and [Studio review protocol](../architecture/review-loop.md)
for sharing feedback.
