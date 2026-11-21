# OpenSMPSDeck

OpenSMPSDeck is an S3K-first SMPS music studio for the Sega Mega Drive /
Genesis. The JavaFX Timeline Studio and headless CLI share the same
`.smps.json` project format. Edit linked clips and notes in Studio, or inspect
and edit the native bank of an imported S3&K song.

Agents work directly on project files. Studio automatically refreshes an open
project after a valid external edit when there are no unsaved local changes.
The CLI can validate, compile and render the same project without opening
Studio. Both use the [pinned OpenGGF audio core](openggf-audio/README.md) for playback.

## Build and run

From the repository root, with JDK 25 and Maven:

```sh
mvn -B -pl studio -am package
java -jar studio/target/opensmpsdeck-studio-0.1.0-SNAPSHOT.jar examples/lantern-walk.smps.json
```

- [Studio user guide](docs/user-guide/studio.md) covers editing, live refresh,
  S3&K import and export.
- [Headless CLI guide](headless/README.md) covers commands and examples for
  reconstruction, validation, compilation and export.
- [External-agent guide](docs/user-guide/agents.md) covers installing the skill,
  connecting MCP and reviewing proposed edits.

Staged reconstruction can recover fast scale passages and same-pitch
retriggers from mapped stems. Read the ordered pitches and timing with
`reconstruct describe` and check the allocated lane in `report.json`.
See [reconstruction stages](docs/architecture/reconstruction-stages.md#stem-backed-fast-figures)
for evidence and frame placement.

The previous tracker is preserved at the `legacy-final` Git tag.

## License

OpenSMPSDeck is licensed under the [GNU General Public License v3.0](LICENSE).
