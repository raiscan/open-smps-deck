# AGENTS.md

Custom agents for OpenSMPSDeck development are available via Claude Code's Agent tool.

## smps-bytecode-helper

**Location** `.claude/agents/smps-bytecode-helper.md`

Use for encoding and decoding SMPS bytecode, constructing test binaries,
debugging track data and checking coordination flag sequences. For S3K flag
values, behavior and parameter lengths, refer to the pinned
[Sonic3kCoordFlagHandler](openggf-audio/src/main/java/com/openggf/game/sonic3k/audio/smps/Sonic3kCoordFlagHandler.java).
Check the target dialect before interpreting bytes.
