# AGENTS.md

OpenSMPSDeck is an S3K-first Sega Mega Drive music studio and headless CLI.
Both use the shared `.smps.json` project format and pinned OpenGGF playback.
See [CLAUDE.md](CLAUDE.md) for the module map and build commands.

## Arrangement workflow

Install the [OpenSMPSDeck skill](skills/opensmpsdeck/SKILL.md) by linking its
folder into `~/.claude/skills` for Claude Code or `~/.agents/skills` for Codex.
The [external-agent guide](docs/user-guide/agents.md) has the build, install
and MCP setup commands.

1. Check a Suno download with `reconstruct import` or Studio's **Import
   Suno folder…**. Verify the stem assignments before starting reconstruction.
2. Run reconstruction, inspect evidence, journal or propose edits, rebuild
   from the affected stage, and prepare A/B audio for review.
3. Export the approved result or open it as an editable arrangement in Studio.

## Repository rules

- This repo is public. Keep private Suno and ROM data, renders, work artifacts
  and their paths outside the checkout. Use synthetic test fixtures and keep
  personal absolute paths out of commits.
- Record musical edits through `edits.json`. Never hand-edit `score.json` or
  `allocation.json`. Propose edits by default while a human is reviewing.
- Before submitting, run `mvn -B -pl headless -am verify` and the
  [MCP smoke test](headless/README.md#embedding-and-verification). Pipeline code
  changes must pass the [quick benchmark gate](docs/architecture/benchmark.md)
  with `bench compare --tolerance 0` for unchanged default behavior.

## smps-bytecode-helper

Use the [Claude Code subagent](.claude/agents/smps-bytecode-helper.md) to encode
or decode SMPS, build synthetic binaries, debug tracks, and check coordination
flags. Identify the target dialect first and use the pinned
[Sonic3kCoordFlagHandler](openggf-audio/src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java)
for S3K flag behavior and parameter lengths.
