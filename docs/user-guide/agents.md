# External agents

Use Claude Code or Codex to drive reconstruction on the same local files that
Studio opens. The headless JAR runs with JDK 25 and needs no audio device.
The skill explains how to turn an aligned MIDI and WAV into an S3K arrangement
and iterate on time-coded feedback.

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

You can copy the folder with `cp -R "$repo/skills/opensmpsdeck" DESTINATION`
instead. Keep the checkout available for the linked workflow guides. If your
session does not see the skill, restart it and invoke `/opensmpsdeck` in Claude
Code or `$opensmpsdeck` in Codex.

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
placeholder with the absolute path to your packaged JAR. Timeout is in
milliseconds. [Claude Code MCP configuration](https://code.claude.com/docs/en/mcp)

```json
{
  "mcpServers": {
    "opensmpsdeck": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/absolute/path/to/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar", "mcp"],
      "timeout": 600000
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

## Drive a review

1. Call `reconstruct_run` with `workDir`, `inputs: {midi, wav, catalogue}`
   and optionally `inputs.stems`, a map of MIDI part IDs to aligned WAV paths.
   `options.seconds` limits the first pass. An initial run requires a new work
   directory under an existing parent. A resumed run takes `workDir` and
   `from`. Reconstruction can take minutes and reports stage progress when the
   client supplies a progress token. Calls on one server run serially.
2. Open the returned `arrangement.smps.json` in Studio. Call `reference_add`
   with the project and WAV. Use `notes_list` with `openOnly: true` to read the
   latest saved notes. Inspect `report` or a stage with `artifact_read`.
3. To edit a single decision, pass an existing `selection` such as
   `["lanes", "FM1", "gain"]` to `artifact_write` with its new `value`.
   Each path component is an object key or zero-based array index, as a
   string. Omit `selection` to replace the whole artifact. The tool validates
   the entire resulting artifact before publishing it. For large arrays,
   local JSON tools can find the index before calling MCP.
4. Rerun from the first stage that consumes your edit. For voicing changes,
   use `from: "assemble"` or `from: "voice"`. Run standalone
   `reconstruct_stage` with `stage: "voice"` to refit after allocation edits.
   MCP assembly preserves the saved references and notes and checks the loaded
   project revision before publication. Use `project_validate` after assembly.
5. Use `render_clip` with the project, a new `output` WAV, and `start` and
   `end` in project seconds. It advances the driver from time zero, rounds
   both boundaries to 60 Hz, and supports optional `solo` and `mute` lists.
   To compare with the source, call `render_compare` with the full time-zero
   render WAV and reference WAV. It writes an A/B of up to 60 seconds per
   side and returns the split point and gains. Use `referenceOffset` for
   the project time where WAV time zero aligns. The comparison attenuates
   to the quieter RMS level when both sides have audio.
6. Listen and call `notes_resolve` with the note ID, reply and author when
   the change is verified. A clean Studio window refreshes from disk. Check
   for unsaved Studio edits before reconciling a disk revision.

Input paths must be absolute and exist. New WAV paths may be relative to or
inside the supplied work directory or project parent. Existing WAVs and
symlink escapes are rejected. Keep work directories, reports and review
projects containing private input paths outside public repositories.

Use the [agent skill](../../skills/opensmpsdeck/SKILL.md) for the edit routing
checklist and CLI fallback. See the
[headless command guide](../../headless/README.md),
[stage artifact reference](../architecture/reconstruction-stages.md),
[Studio review protocol](../architecture/review-loop.md), and
[benchmark guide](../architecture/benchmark.md) for details.
